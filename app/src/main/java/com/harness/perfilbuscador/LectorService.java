package com.harness.perfilbuscador;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

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
        void onAutoBrowse(boolean activo, int swipes);
    }

    public static Escucha escucha;

    private static final Handler H = new Handler(Looper.getMainLooper());

    /** Nombre del perfil cuyo chat hay que abrir; null si no hay peticion. */
    private static String pendiente;
    /** Texto a colocar en el campo de mensaje. */
    private static String borrador = "";
    /** Cuántos reintentos llevamos esperando a que se abra el chat. */
    private static int faseApertura = 0;
    /** Coordenadas ya conocidas de la fila, para un toque directo. */
    private static int filaX;
    private static int filaY;
    private static boolean hayCoordenadas;
    /** Reintentos mientras se busca la fila del perfil. */
    private static int intentos = 0;

    private long ultimaLectura = 0;
    private static final long INTERVALO = 900;

    private static final String OPEN_GRIND = "org.opengrind";
    private static final String GRINDR = "com.grindrapp.android";

    private static final int SWIPE_DURATION = 120;
    private static final long SWIPE_DELAY = 650;
    private static final int MAX_SWIPES = 80;
    private static final String FILTRO_FIN = "Fresh"; // aparece al llegar al final

    private boolean autoActivo = false;
    private int autoSwipes = 0;
    private Handler autoH = new Handler(Looper.getMainLooper());
    private Runnable autoRunnable = new Runnable() {
        @Override public void run() { if (autoActivo) hacerSwipe(); }
    };

    private void iniciarAutoSiCorresponde() {
        if (autoActivo) {
            autoH.removeCallbacksAndMessages(null);
            autoH.post(autoRunnable);
        }
    }

    /**
     * Pide abrir el chat de un perfil. El usuario decide: solo responde a un
     * toque en una fila de la lista, nunca por su cuenta.
     */
    public static void abrirChat(Context ctx, String nombre, String texto) {
        abrirChat(ctx, nombre, texto, 0, 0);
    }

    /**
     * Abre el chat de un perfil ya visto en pantalla. Con las coordenadas de la
     * fila no hace falta volver a buscarlo: se abre la app y se toca.
     *
     * El usuario decide: esto solo responde a un toque en una fila de su lista,
     * nunca envia nada por su cuenta.
     */
    private static final String TAG = "PerfilBuscador";

    private static void logArchivo(String m) {
        try {
            java.io.File f = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "perfilbuscador.log");
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + m + "\n");
            fw.close();
        } catch (Exception ignored) {
        }
    }

    private static void log(String m) {
        try {
            android.util.Log.i(TAG, m);
        } catch (Exception ignored) {
        }
    }

    public static void abrirChat(Context ctx, String nombre, String texto, int x, int y) {
        log("abrirChat " + nombre + " en " + x + "," + y + " borrador=" + (texto == null ? 0 : texto.length()));
        pendiente = nombre;
        borrador = texto == null ? "" : texto;
        faseApertura = 0;
        intentos = 0;
        hayCoordenadas = x > 0 && y > 0;
        filaX = x;
        filaY = y;
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
        // segundo intento: por si la app aun no estaba lista
        H.postDelayed(() -> {
            LectorService s = instancia;
            if (s != null && hayCoordenadas) {
                AccessibilityNodeInfo r = s.obtenerRaiz();
                if (r != null) {
                    s.intentarAbrirChat(r);
                }
            }
        }, 1800);
    }

    public static void abrirPerfil(Context ctx, String nombre, int x, int y) {
        log("abrirPerfil " + nombre + " en " + x + "," + y);
        logArchivo("abrirPerfil " + nombre + " en " + x + "," + y);
        if (ctx == null) {
            return;
        }
        // Mostrar toast para confirmar que se ejecuta
        try {
            android.widget.Toast.makeText(ctx, "Abriendo perfil: " + nombre, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
        // Abrir Open Grind primero
        H.postDelayed(() -> {
            try {
                Intent i = new Intent();
                i.setClassName(OPEN_GRIND, OPEN_GRIND + ".MainActivity");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                ctx.startActivity(i);
                logArchivo("Open Grind abierto");
            } catch (Exception e) {
                logArchivo("Error al abrir Open Grind: " + e.getMessage());
            }
        }, 300);

        // Reintentos: a veces Open Grind tarda en dejar la lista lista
        long[] delays = {1200, 1800, 2600, 3400, 4200};
        for (int i = 0; i < delays.length; i++) {
            long delay = delays[i];
            H.postDelayed(() -> {
                LectorService s = instancia;
                if (s == null) {
                    return;
                }
                AccessibilityNodeInfo r = s.obtenerRaiz();
                if (r == null) {
                    return;
                }
                try {
                    AccessibilityNodeInfo nodo = s.buscarNodoPorTexto(r, nombre);
                    if (nodo != null) {
                        android.graphics.Rect bounds = new android.graphics.Rect();
                        nodo.getBoundsInScreen(bounds);
                        if (!bounds.isEmpty()) {
                            boolean click = s.tocar(nodo);
                            log("intento " + delay + " click=" + click
                                    + " bounds=" + bounds.centerX() + ","
                                    + bounds.centerY());
                        } else {
                            AccessibilityNodeInfo clickable = s.buscarPadreClickable(nodo);
                            if (clickable != null) {
                                boolean ok = s.tocar(clickable);
                                log("intento " + delay + " padre clickable=" + ok);
                            }
                        }
                    } else if (x > 0 && y > 0) {
                        boolean ok = s.dispatchGesture(s.toque(x, y), null, null);
                        log("intento " + delay + " gesto fallback=" + ok);
                    }
                } finally {
                    r.recycle();
                }
            }, delay);
        }
    }

    private static LectorService instancia;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instancia = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.packageNames = new String[]{OPEN_GRIND, GRINDR};
        setServiceInfo(info);
    }

    public static void cancelar() {
        pendiente = null;
        borrador = "";
        faseApertura = 0;
        hayCoordenadas = false;
    }

    public void setAutoActivo(boolean activo) {
        autoActivo = activo;
        autoSwipes = 0;
        if (activo) {
            autoH.removeCallbacksAndMessages(null);
            autoH.post(autoRunnable);
        } else {
            autoH.removeCallbacksAndMessages(null);
        }
        notificarAuto();
    }

    public static void setAutoEstatico(boolean activo) {
        if (instancia != null) {
            instancia.setAutoActivo(activo);
        }
    }

    public boolean isAutoActivo() {
        return autoActivo;
    }

    private void notificarAuto() {
        if (escucha != null) {
            escucha.onAutoBrowse(autoActivo, autoSwipes);
        }
    }

    private void hacerSwipe() {
        if (!autoActivo) {
            return;
        }
        if (autoSwipes >= MAX_SWIPES) {
            setAutoActivo(false);
            return;
        }
        AccessibilityNodeInfo raiz = getRootInActiveWindow();
        if (raiz == null) {
            autoH.postDelayed(autoRunnable, SWIPE_DELAY);
            return;
        }
        boolean llegamosAlFinal = yaLlegoAlFinal(raiz);
        if (llegamosAlFinal) {
            setAutoActivo(false);
            raiz.recycle();
            return;
        }
        Path p = new Path();
        p.moveTo(540, 1700);
        p.lineTo(540, 300);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, SWIPE_DURATION))
                .build(), null, null);
        autoSwipes++;
        notificarAuto();
        raiz.recycle();
        autoH.postDelayed(autoRunnable, SWIPE_DELAY);
    }

    private boolean yaLlegoAlFinal(AccessibilityNodeInfo raiz) {
        StringBuilder sb = new StringBuilder();
        recolectar(raiz, sb);
        return sb.toString().toLowerCase().contains(FILTRO_FIN.toLowerCase());
    }

    private void recolectar(AccessibilityNodeInfo nodo, StringBuilder salida) {
        for (AccessibilityNodeInfo n : todos(nodo)) {
            CharSequence t = n.getText();
            if (t != null) {
                String s = t.toString().trim();
                if (!s.isEmpty()) {
                    salida.append(s).append(' ');
                }
            }
        }
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

        AccessibilityNodeInfo raiz = obtenerRaiz();
        if (raiz == null) {
            return;
        }

        if (pendiente != null) {
            intentarAbrirChat(raiz);
            return;
        }

        if (autoActivo) {
            hacerSwipe();
            return;
        }

        long ahora = System.currentTimeMillis();
        if (ahora - ultimaLectura < INTERVALO) {
            return;
        }
        ultimaLectura = ahora;

        List<Perfil> perfiles = extraer(raiz);
        if (escucha != null && !perfiles.isEmpty()) {
            escucha.onPerfiles(perfiles);
        }
    }

    /**
     * Raíz de la ventana activa. getRootInActiveWindow() devuelve a veces la
     * ventana equivocada cuando hay WebView, así que se recorren todas.
     */
    private AccessibilityNodeInfo obtenerRaiz() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null) {
            return r;
        }
        List<AccessibilityWindowInfo> ventanas = getWindows();
        for (AccessibilityWindowInfo w : ventanas) {
            AccessibilityNodeInfo n = w.getRoot();
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    /**
     * Clic en el perfil y escritura del borrador. Si el perfil aun no esta en
     * pantalla, se reintenta: Open Grind tarda en cargar y al principio sale el
     * splash, no la lista.
     */
    private boolean intentarAbrirChat(AccessibilityNodeInfo raiz) {
        log("intentar: pendiente=" + pendiente + " fase=" + faseApertura
                + " coords=" + hayCoordenadas + " intentos=" + intentos);
        // 0) si ya sabemos donde esta la fila, un toque y listo
        if (hayCoordenadas && faseApertura == 0) {
            // toque largo y un segundo intento: si el primero cae en el sitio
            // equivocado, repetimos con el nodo resuelto por texto
            boolean ok = dispatchGesture(toque(filaX, filaY), null, null);
            log("gesto en " + filaX + "," + filaY + " -> " + ok);
            if (ok) {
                faseApertura = 1;
                intentos = 0;
                H.postDelayed(() -> repetirSiSigueEnLaLista(filaX, filaY), 2500);
                return true;
            }
        }

        AccessibilityNodeInfo fila = buscarFila(raiz, pendiente);
        log("buscarFila(" + pendiente + ") -> " + (fila != null));

        if (fila != null) {
            android.graphics.Rect r = new android.graphics.Rect();
            fila.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                hayCoordenadas = true;
                filaX = r.centerX();
                filaY = r.centerY();
            }
            if (tocar(fila)) {
                faseApertura = 1;
                intentos = 0;
                H.postDelayed(() -> repetirSiSigueEnLaLista(filaX, filaY), 2500);
                return true;
            }
        }

        // dentro del chat: escribir el borrador
        if (faseApertura > 0) {
            if (escribirEnCampo(raiz)) {
                pendiente = null;
                faseApertura = 0;
                return true;
            }
        }

        // reintentar el gesto: puede que el primero haya caído antes de que la
        // app terminara de abrir
        if (faseApertura == 1 && hayCoordenadas) {
            dispatchGesture(toque(filaX, filaY), null, null);
        }

        if (intentos < 25) {
            intentos++;
            H.postDelayed(() -> {
                AccessibilityNodeInfo r = obtenerRaiz();
                if (r != null) {
                    intentarAbrirChat(r);
                }
            }, 500);
        } else {
            pendiente = null;
            faseApertura = 0;
        }
        return false;
    }

    /** Gesto de toque en un punto. */
    private GestureDescription toque(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
    }

    /**
     * Si tras el toque seguimos en la lista, es que el primer toque no sirvio:
     * se resuelve el perfil por su nombre otra vez y se toca el nodo bueno.
     */
    private void repetirSiSigueEnLaLista(int x, int y) {
        AccessibilityNodeInfo raiz = obtenerRaiz();
        if (raiz == null) {
            return;
        }
        // si ya no esta la lista, el perfil abrio: escribir el borrador
        boolean sigueLista = false;
        for (AccessibilityNodeInfo n : todos(raiz)) {
            CharSequence c = n.getText();
            if (c != null && c.toString().trim().equalsIgnoreCase(String.valueOf(pendiente))) {
                sigueLista = true;
                break;
            }
        }
        log("tras el toque sigueLista=" + sigueLista);

        if (sigueLista) {
            // el nodo de texto tiene bounds 0,0: tocar la celda de la cuadricula
            List<int[]> celdas = celdasDeCuadricula(raiz);
            List<Perfil> perfiles = extraer(raiz);
            for (Perfil p : perfiles) {
                if (p.nombre.equalsIgnoreCase(String.valueOf(pendiente)) && p.x > 0) {
                    log("reintento en celda " + p.x + "," + p.y);
                    dispatchGesture(toque(p.x, p.y), null, null);
                    faseApertura = 1;
                    H.postDelayed(this::escribirBorrador, 2500);
                    return;
                }
            }
            if (!celdas.isEmpty()) {
                dispatchGesture(toque(celdas.get(0)[0], celdas.get(0)[1]), null, null);
            }
            return;
        }

        // abrio el perfil: poner el borrador
        escribirEnCampo(raiz);
        if (!escribirEnCampo(raiz)) {
            H.postDelayed(this::escribirBorrador, 1500);
        } else {
            pendiente = null;
            faseApertura = 0;
        }
    }

    /**
     * Toca un nodo. Primero usa la acción de accesibilidad; si no surte efecto
     * —que es lo que pasa con los nodos virtuales de WebView— hace un gesto
     * táctil en su centro, que es lo que ve la persona.
     */
    private boolean tocar(AccessibilityNodeInfo nodo) {
        AccessibilityNodeInfo pulsable = pulsar(nodo);
        if (pulsable != null) {
            if (pulsable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }
        if (nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }

        android.graphics.Rect r = new android.graphics.Rect();
        nodo.getBoundsInScreen(r);
        if (r.isEmpty()) {
            return false;
        }
        return dispatchGesture(toque(r.centerX(), r.centerY()), null, null);
    }

    private AccessibilityNodeInfo buscarNodoPorTexto(AccessibilityNodeInfo raiz, String texto) {
        if (texto == null || texto.isEmpty()) {
            return null;
        }
        String objetivo = texto.trim().toLowerCase();
        List<AccessibilityNodeInfo> todos = todos(raiz);
        for (AccessibilityNodeInfo n : todos) {
            CharSequence c = n.getText();
            if (c != null && c.toString().toLowerCase().contains(objetivo)) {
                return n;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo buscarPadreClickable(AccessibilityNodeInfo nodo) {
        AccessibilityNodeInfo p = nodo.getParent();
        while (p != null) {
            if (p.isClickable()) {
                return p;
            }
            p = p.getParent();
        }
        return null;
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
            log("sin borrador, nada que escribir");
            return true;
        }
        int campos = 0;
        for (AccessibilityNodeInfo n : todos(raiz)) {
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            if (!cls.contains("EditText")) {
                continue;
            }
            campos++;
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, borrador);
            if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                log("texto escrito en un EditText");
                return true;
            }
        }
        log("EditText encontrados: " + campos);
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
            CharSequence ct = n.getText();
            if (ct == null) {
                continue;
            }
            android.graphics.Rect rr = new android.graphics.Rect();
            n.getBoundsInScreen(rr);
            Perfil p = interpretar(ct.toString(), rr.centerX(), rr.centerY(),
                    new ArrayList<int[]>(), 0);
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
        List<int[]> celdas = celdasDeCuadricula(raiz);
        log("celdas de la cuadricula: " + celdas.size());

        int fila = 0;
        for (AccessibilityNodeInfo n : todos(raiz)) {
            if (!n.isClickable()) {
                continue;
            }
            CharSequence t = n.getText();
            if (t == null) {
                continue;
            }
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            Perfil p = interpretar(t.toString(), r.centerX(), r.centerY(), celdas, fila);
            if (p == null) {
                continue;
            }
            fila++;
            log("perfil " + fila + ": " + p.nombre + " @ " + p.x + "," + p.y);
            lista.add(p);
        }
        if (!lista.isEmpty()) {
            return lista;
        }

        // respaldo: si no hay filas pulsables, agrupar por distancia
        return agrupar(raiz);
    }

    /**
     * Convierte "277 m Online now qn piola" en distancia 277 m y nombre "qn piola".
     *
     * El nodo de texto de la fila viene con bounds [0,0][0,0]: la WebView no
     * le da geometria al texto. Por eso se usan las celdas de la cuadricula, que
     * si la tienen y siguen la retícula (y = 317, 649, 981, 1313…).
     */
    private Perfil interpretar(String bruto, int cx, int cy, List<int[]> celdas, int filaActual) {
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

        // si el nodo no tiene geometria, usar la celda que le corresponde por orden
        if (cx <= 0 || cy <= 0) {
            if (celdas.isEmpty()) {
                return new Perfil(distancia, resto);
            }
            int idx = Math.min(filaActual, celdas.size() - 1);
            int[] c = celdas.get(idx);
            return new Perfil(distancia, resto, c[0], c[1]);
        }
        return new Perfil(distancia, resto, cx, cy);
    }

    /**
     * Extrae las celdas de la cuadricula: View sin texto con coordenadas reales.
     * Van en orden de pantalla, asi que su indice es la fila del perfil.
     */
    private List<int[]> celdasDeCuadricula(AccessibilityNodeInfo raiz) {
        List<int[]> celdas = new ArrayList<>();
        for (AccessibilityNodeInfo n : todos(raiz)) {
            if (n.isClickable()) {
                continue;
            }
            CharSequence t = n.getText();
            if (t != null && !t.toString().trim().isEmpty()) {
                continue;
            }
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            if (r.isEmpty() || r.width() < 100 || r.height() < 100) {
                continue;
            }
            // solo las que estan en la zona de la cuadrícula
            if (r.top < 260 || r.top > 2200) {
                continue;
            }
            celdas.add(new int[]{r.centerX(), r.centerY()});
        }
        celdas.sort((a, b) -> a[1] - b[1]);
        return celdas;
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
        /** Centro de la fila en pantalla, para poder tocarla sin accesibilidad. */
        public final int x;
        public final int y;

        public Perfil(String distancia, String nombre) {
            this(distancia, nombre, 0, 0);
        }

        public Perfil(String distancia, String nombre, int x, int y) {
            this.distancia = distancia == null ? "" : distancia;
            this.nombre = nombre;
            this.x = x;
            this.y = y;
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
