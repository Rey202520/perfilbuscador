package com.harness.perfilbuscador;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Lee la pantalla de Open Grind y extrae los perfiles visibles.
 *
 * Open Grind es una WebView: los textos llegan como nodos accesibles normales,
 * asi que no hace falta OCR. Cada perfil aparece como un bloque con el nombre
 * y, si esta visible, la distancia (por ejemplo "862 m" o "1.7 km").
 */
public class LectorService extends AccessibilityService {

    /** Callback hacia la Activity; null cuando la app esta en segundo plano. */
    public interface Escucha {
        void onPerfiles(List<Perfil> perfiles);

        void onChatVisible(String nombre);
    }

    public static Escucha escucha;

    private long ultimaLectura = 0;
    private static final long INTERVALO = 1500;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.packageNames = new String[]{"org.opengrind", "com.grindrapp.android"};
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence paquete = event.getPackageName();
        String nombre = paquete == null ? "" : paquete.toString();
        if (!nombre.contains("opengrind") && !nombre.contains("grindrapp")) {
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
        List<Perfil> perfiles = extraer(raiz);
        if (escucha != null && !perfiles.isEmpty()) {
            escucha.onPerfiles(perfiles);
        }
    }

    /** Agrupa los textos de la pantalla en perfiles por distancia. */
    private List<Perfil> extraer(AccessibilityNodeInfo raiz) {
        List<Perfil> lista = new ArrayList<>();
        List<String> textos = new ArrayList<>();
        recolectar(raiz, textos);

        StringBuilder nombre = null;
        for (String t : textos) {
            if (esDistancia(t)) {
                if (nombre != null && nombre.length() > 0) {
                    lista.add(new Perfil(distanciaActual, nombre.toString()));
                    nombre = null;
                }
                distanciaActual = t;
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
            lista.add(new Perfil(distanciaActual, nombre.toString()));
        }
        return lista;
    }

    private String distanciaActual = "";

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
                || s.equals("distance")
                || s.isEmpty();
    }

    private void recolectar(AccessibilityNodeInfo nodo, List<String> salida) {
        if (nodo == null) {
            return;
        }
        CharSequence t = nodo.getText();
        if (t != null) {
            String s = t.toString().trim();
            if (!s.isEmpty()) {
                salida.add(s);
            }
        }
        for (int i = 0; i < nodo.getChildCount(); i++) {
            recolectar(nodo.getChild(i), salida);
        }
    }

    @Override
    public void onInterrupt() {
    }

    /** Perfil con su distancia y su nombre. */
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
