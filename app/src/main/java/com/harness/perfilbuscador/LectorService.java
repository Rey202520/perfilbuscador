package com.harness.perfilbuscador;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Lee la pantalla de Open Grind y abre el chat del perfil que elija el usuario.
 *
 * Open Grind no declara deep links, asi que no hay URL para saltar a un chat.
 * En su lugar este servicio localiza el nodo del perfil en la pantalla actual
 * y le hace clic; despues escribe el borrador en el campo de texto para que
 * sea la persona quien lo envie.
 */
public class LectorService extends AccessibilityService {

    public interface Escucha {
        void onPerfiles(List<Perfil> perfiles);
    }

    public static Escucha escucha;

    private static final Handler H = new Handler(Looper.getMainLooper());

    /** Nombre del perfil cuyo chat hay que abrir; null si no hay peticion. */
    private static String pendiente;
    /** Texto a colocar en el campo de mensaje. */
    private static String borrador = "";
    /** Cuántos reintentos llevamos esperando a que se abra el chat. */
    private static int faseApertura = 0;

    private long ultimaLectura = 0;
    private static final long INTERVALO = 900;

    private static final String OPEN_GRIND = "org.opengrind";
    private static final String GRINDR = "com.grindrapp.android";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.packageNames = new String[]{OPEN_GRIND, GRINDR};
        setServiceInfo(info);
    }

    /**
     * Pide abrir el chat de un perfil. El usuario decide: solo responde a un
     * toque en una fila de la lista, nunca por su cuenta.
     */
    public static void abrirChat(Context ctx, String nombre, String texto) {
        pendiente = nombre;
        borrador = texto == null ? "" : texto;
        faseApertura = 0;
        if (ctx == null) {
            return;
        }
        copiar(ctx, texto);
        H.postDelayed(() -> {
            try {
                Intent i = new Intent();
                i.setClassName(OPEN_GRIND, OPEN_GRIND + ".MainActivity");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                ctx.startActivity(i);
            } catch (Exception ignored) {
            }
        }, 250);
    }

    public static void cancelar() {
        pendiente = null;
        borrador = "";
        faseApertura = 0;
    }

    private static void copiar(Context ctx, String texto) {
        if (ctx == null || texto == null || texto.trim().isEmpty()) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("mensaje", texto));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence paquete = event.getPackageName();
        String nombre = paquete == null ? "" : paquete.toString();
        if (!nombre.contains(OPEN_GRIND) && !nombre.contains(GRINDR)) {
            return;
        }

        long ahora = System.currentTimeMillis();
        if (ahora - ultimaLectura < INTERVALO) {
            return;
        }
        ultimaLectura = ahora;

        AccessibilityNodeInfo raiz = getRootInActiveWindow();
        if (raiz == null) {
            return;
        }

        // Si hay una peticion pendiente,Gift prioridad: abrir el chat.
        if (pendiente != null) {
            if (intentarAbrirChat(raiz)) {
                return;
            }
        }

        List<Perfil> perfiles = extraer(raiz);
        if (escucha != null && !perfiles.isEmpty()) {
            escucha.onPerfiles(perfiles);
        }
    }

    /** Clic en el perfil, y luego escritura del borrador. */
    private boolean intentarAbrirChat(AccessibilityNodeInfo raiz) {
        // 1) localizar la fila del perfil y pulsarla directamente
        AccessibilityNodeInfo fila = buscarFila(raiz, pendiente);
        if (fila != null && fila.isClickable()) {
            fila.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            faseApertura = 1;
            H.postDelayed(this::escribirBorrador, 1800);
            return true;
        }
        // si el nodo encontrado no es pulsable, subir al ancestro que lo sea
        if (fila != null) {
            AccessibilityNodeInfo pulsable = pulsar(fila);
            if (pulsable != null) {
                pulsable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                faseApertura = 1;
                H.postDelayed(this::escribirBorrador, 1800);
                return true;
            }
        }

        // 2) si ya estamos dentro del chat, escribir el borrador
        if (faseApertura > 0) {
            if (escribirEnCampo(raiz)) {
                pendiente = null;
                faseApertura = 0;
                return true;
            }
            if (faseApertura < 12) {
                int f = faseApertura + 1;
                faseApertura = f;
                H.postDelayed(() -> {
                    AccessibilityNodeInfo r = getRootInActiveWindow();
                    if (r != null) {
                        intentarAbrirChat(r);
                    }
                }, 600);
                return true;
            }
            pendiente = null;
            faseApertura = 0;
        }
        return false;
    }

    private void escribirBorrador() {
        AccessibilityNodeInfo raiz = getRootInActiveWindow();
        if (raiz == null) {
            return;
        }
        if (!escribirEnCampo(raiz)) {
            intentarAbrirChat(raiz);
        }
    }

    /** Escribe el borrador en el EditText del chat. */
    private boolean escribirEnCampo(AccessibilityNodeInfo raiz) {
        if (borrador == null || borrador.trim().isEmpty()) {
            return true;
        }
        for (AccessibilityNodeInfo n : todos(raiz)) {
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            if (!cls.contains("EditText")) {
                continue;
            }
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, borrador);
            if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Localiza la fila del perfil. Busca primero en los nodos pulsables, que son
     * los que llevan "277 m Online now qn piola"; si no, cae a cualquier nodo
     * cuyo texto contenga el nombre.
     */
    private AccessibilityNodeInfo buscarFila(AccessibilityNodeInfo raiz, String texto) {
        if (texto == null || raiz == null) {
            return null;
        }
        String t = texto.trim().toLowerCase();

        for (AccessibilityNodeInfo n : todos(raiz)) {
            if (!n.isClickable()) {
                continue;
            }
            Perfil p = interpretar(String.valueOf(n.getText()));
            if (p != null && p.nombre.toLowerCase().contains(t)) {
                return n;
            }
        }
        for (AccessibilityNodeInfo n : todos(raiz)) {
            CharSequence c = n.getText();
            if (c != null && c.toString().trim().toLowerCase().equals(t)) {
                return n;
            }
        }
        for (AccessibilityNodeInfo n : todos(raiz)) {
            CharSequence c = n.getText();
            if (c != null && c.toString().toLowerCase().contains(t)) {
                return n;
            }
        }
        return null;
    }

    /**
     * Sube por los ancestros hasta un nodo pulsable. En la cuadrícula de Open
     * Grind el texto del nombre no es pulsable, pero el contenedor que lo
     * envuelve si.
     */
    private AccessibilityNodeInfo pulsar(AccessibilityNodeInfo nodo) {
        AccessibilityNodeInfo actual = nodo;
        for (int i = 0; i < 6 && actual != null; i++) {
            if (actual.isClickable() && actual.isEnabled()) {
                return actual;
            }
            actual = actual.getParent();
        }
        return null;
    }

    private List<AccessibilityNodeInfo> todos(AccessibilityNodeInfo raiz) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (raiz == null) {
            return out;
        }
        out.add(raiz);
        for (int i = 0; i < raiz.getChildCount(); i++) {
            out.addAll(todos(raiz.getChild(i)));
        }
        return out;
    }

    /**
     * Agrupa los perfiles de la pantalla.
     *
     * La fila completa de Open Grind es un unico nodo View con click=true y texto
     * del tipo "277 m Online now qn piola". Ese nodo es el que hay que pulsar.
     * Los TextView hijos ("277 m", "Online now", "qn piola") NO son pulsables,
     * asi que ignorarlos evita duplicados.
     */
    private List<Perfil> extraer(AccessibilityNodeInfo raiz) {
        List<Perfil> lista = new ArrayList<>();

        for (AccessibilityNodeInfo n : todos(raiz)) {
            if (!n.isClickable()) {
                continue;
            }
            CharSequence t = n.getText();
            if (t == null) {
                continue;
            }
            Perfil p = interpretar(t.toString());
            if (p != null) {
                lista.add(p);
            }
        }
        if (!lista.isEmpty()) {
            return lista;
        }

        // respaldo: si no hay filas pulsables, agrupar por distancia
        return agrupar(raiz);
    }

    /** Convierte "277 m Online now qn piola" en distancia 277 m y nombre "qn piola". */
    private Perfil interpretar(String bruto) {
        String s = bruto.trim();
        if (s.isEmpty()) {
            return null;
        }
        int corte = s.indexOf(' ');
        if (corte <= 0) {
            return null;
        }
        String distancia = s.substring(0, corte).trim();
        if (!distancia.matches("^\\d+(?:[.,]\\d+)?\\s*(m|km)$")) {
            return null;
        }
        String resto = s.substring(corte + 1).trim();
        for (String pre : ESTADOS) {
            if (resto.toLowerCase().startsWith(pre)) {
                resto = resto.substring(pre.length()).trim();
                break;
            }
        }
        if (resto.isEmpty() || esRuido(resto)) {
            return null;
        }
        return new Perfil(distancia, resto);
    }

    private static final String[] ESTADOS = {
            "Online now. Visiting ",
            "Online now ",
            "Online ",
            "Visiting ",
            "Recently active ",
            "Active recently ",
    };

    /** Agrupado por distancia, para cuando las filas no son pulsables. */
    private List<Perfil> agrupar(AccessibilityNodeInfo raiz) {
        List<Perfil> lista = new ArrayList<>();
        List<String> textos = new ArrayList<>();
        recolectar(raiz, textos);

        String distancia = "";
        StringBuilder nombre = null;
        for (String t : textos) {
            if (esDistancia(t)) {
                if (nombre != null && nombre.length() > 0) {
                    lista.add(new Perfil(distancia, nombre.toString()));
                    nombre = null;
                }
                distancia = t;
            } else if (esRuido(t)) {
                continue;
            } else {
                if (nombre == null) {
                    nombre = new StringBuilder();
                } else {
                    nombre.append(' ');
                }
                nombre.append(t);
            }
        }
        if (nombre != null && nombre.length() > 0) {
            lista.add(new Perfil(distancia, nombre.toString()));
        }
        return lista;
    }

    private boolean esDistancia(String t) {
        return t.matches("^\\d+(?:[.,]\\d+)?\\s*(m|km)\\b.*$");
    }

    /** Filtra los textos de la interfaz que no son nombres de perfil. */
    private boolean esRuido(String t) {
        String s = t.toLowerCase();
        return s.startsWith("online now")
                || s.startsWith("online")
                || s.startsWith("visiting")
                || s.startsWith("right now")
                || s.startsWith("someone")
                || s.startsWith("browse")
                || s.startsWith("inbox")
                || s.startsWith("interest")
                || s.startsWith("filters")
                || s.startsWith("favorites")
                || s.startsWith("age")
                || s.startsWith("gender")
                || s.startsWith("position")
                || s.equals("m")
                || s.equals("km")
                || s.equals("apply")
                || s.equals("none")
                || s.equals("fresh")
                || s.isEmpty();
    }

    private void recolectar(AccessibilityNodeInfo nodo, List<String> salida) {
        for (AccessibilityNodeInfo n : todos(nodo)) {
            CharSequence t = n.getText();
            if (t != null) {
                String s = t.toString().trim();
                if (!s.isEmpty()) {
                    salida.add(s);
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        cancelar();
        super.onDestroy();
    }

    /** Un perfil con su distancia y su nombre. */
    public static class Perfil {
        public final String distancia;
        public final String nombre;

        public Perfil(String distancia, String nombre) {
            this.distancia = distancia == null ? "" : distancia;
            this.nombre = nombre;
        }

        public boolean coincide(List<String> palabras) {
            if (palabras == null || palabras.isEmpty()) {
                return true;
            }
            String n = nombre.toLowerCase();
            for (String p : palabras) {
                String q = p.trim().toLowerCase();
                if (!q.isEmpty() && n.contains(q)) {
                    return true;
                }
            }
            return false;
        }
    }
}
