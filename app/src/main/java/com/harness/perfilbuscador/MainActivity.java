package com.harness.perfilbuscador;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Visor de perfiles de Open Grind: filtro en linea + palabras multiples.
 *
 * Lee lo que hay en pantalla mientras el usuario navega en Open Grind y
 * filtra. No envia mensajes ni accede a cuentas: solo muestra lo que ya se ve.
 */
public class MainActivity extends android.app.Activity {

    private TextView estado;
    private TextView resumen;
    private EditText palabras;
    private LinearLayout lista;
    private Button irAOpenGrind;

    private final List<LectorService.Perfil> todos = new ArrayList<>();
    private List<String> filtros = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#0a0a0a"));

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(dp(14), dp(16), dp(14), dp(24));
        raiz.setBackgroundColor(Color.parseColor("#0a0a0a"));

        TextView titulo = texto("Perfiles en línea", 20, Color.WHITE, true);
        raiz.addView(titulo);

        TextView sub = texto("Lee lo que ves en Open Grind y filtra. Tú eliges.", 13,
                Color.parseColor("#8a8a8a"), false);
        raiz.addView(sub);

        // ---- palabras ----
        TextView etiqueta = texto("Palabras (separadas por coma)", 12,
                Color.parseColor("#8a8a8a"), false);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(18);
        etiqueta.setLayoutParams(mp);
        raiz.addView(etiqueta);

        palabras = new EditText(this);
        palabras.setHint("busco, activo, alta, mierda…");
        palabras.setTextColor(Color.WHITE);
        palabras.setHintTextColor(Color.parseColor("#555555"));
        palabras.setBackgroundColor(Color.parseColor("#151515"));
        palabras.setPadding(dp(12), dp(10), dp(12), dp(10));
        palabras.setSingleLine(false);
        raiz.addView(palabras);

        palabras.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                aplicarFiltros();
            }
        });

        resumen = texto("", 13, Color.parseColor("#5ac8fa"), false);
        LinearLayout.LayoutParams mr = new LinearLayout.LayoutParams(-1, -2);
        mr.topMargin = dp(12);
        resumen.setLayoutParams(mr);
        raiz.addView(resumen);

        // ---- botón para abrir Open Grind ----
        irAOpenGrind = boton("Abrir Open Grind", false);
        irAOpenGrind.setOnClickListener(v -> {
            try {
                startActivity(new Intent().setClassName("org.opengrind",
                        "org.opengrind.MainActivity"));
            } catch (Exception e) {
                toast("No se encontró Open Grind");
            }
        });
        LinearLayout.LayoutParams mb = new LinearLayout.LayoutParams(-1, -2);
        mb.topMargin = dp(10);
        irAOpenGrind.setLayoutParams(mb);
        raiz.addView(irAOpenGrind);

        // ---- lista ----
        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(lista);
        raiz.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        estado = texto("Esperando permiso de accesibilidad…", 13,
                Color.parseColor("#8a8a8a"), false);
        LinearLayout.LayoutParams me = new LinearLayout.LayoutParams(-1, -2);
        me.topMargin = dp(12);
        estado.setLayoutParams(me);
        raiz.addView(estado);

        setContentView(raiz);

        LectorService.escucha = new LectorService.Escucha() {
            @Override
            public void onPerfiles(List<LectorService.Perfil> nuevos) {
                runOnUiThread(() -> {
                    todos.clear();
                    todos.addAll(nuevos);
                    aplicarFiltros();
                });
            }

            @Override
            public void onChatVisible(String nombre) {
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean activo = enabledService();
        estado.setText(activo
                ? "✓ Accesibilidad activa — abre Open Grind y navega"
                : "⚠ Falta el permiso de accesibilidad");
        estado.setTextColor(Color.parseColor(activo ? "#4ade80" : "#f87171"));
    }

    private boolean enabledService() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(LectorService.class.getName());
        } catch (Exception e) {
            return false;
        }
    }

    private void aplicarFiltros() {
        String crudo = palabras.getText().toString();
        filtros = new ArrayList<>();
        for (String p : crudo.split(",")) {
            if (!p.trim().isEmpty()) {
                filtros.add(p.trim().toLowerCase(Locale.ROOT));
            }
        }

        lista.removeAllViews();
        int mostrados = 0;
        for (LectorService.Perfil p : todos) {
            if (!p.coincide(filtros)) {
                continue;
            }
            mostrados++;
            lista.addView(fila(p));
        }
        if (todos.isEmpty()) {
            lista.addView(texto("Sin perfiles leídos todavía.\nAbre Open Grind y desliza "
                    + "para que aparezca la lista.", 14, Color.parseColor("#8a8a8a"), false));
        }
        resumen.setText(filtros.isEmpty()
                ? todos.size() + " perfiles en pantalla"
                : mostrados + " de " + todos.size() + " coinciden con «" + crudo.trim() + "»");
    }

    private View fila(LectorService.Perfil p) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(12), dp(11), dp(12), dp(11));
        v.setBackgroundColor(Color.parseColor("#151515"));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.bottomMargin = dp(6);
        v.setLayoutParams(mp);

        LinearLayout textos = new LinearLayout(this);
        textos.setOrientation(LinearLayout.VERTICAL);

        TextView nombre = texto(p.nombre, 15, Color.WHITE, true);
        textos.addView(nombre);
        if (!p.distancia.isEmpty()) {
            textos.addView(texto(p.distancia, 12, Color.parseColor("#8a8a8a"), false));
        }

        TextView dist = texto(p.distancia.isEmpty() ? "" : p.distancia, 13,
                Color.parseColor("#5ac8fa"), false);
        dist.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        v.addView(textos, new LinearLayout.LayoutParams(0, -2, 1f));
        v.addView(dist);
        return v;
    }

    // ---- utilidades ----
    private TextView texto(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private Button boton(String s, boolean primario) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setTextColor(primario ? Color.parseColor("#04202e") : Color.WHITE);
        b.setBackgroundColor(primario ? Color.parseColor("#5ac8fa") : Color.parseColor("#151515"));
        b.setAllCaps(false);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
