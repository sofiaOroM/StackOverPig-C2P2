package sofiaO.Traductor.riscv;

import sofiaO.semantico.ResolvedType;
import sofiaO.util.Type;

import java.util.*;

/**
 * Todo lo que se sabe de estructuras y clases en memoria (HEAP):
 *  - offset y tamaño de cada campo,
 *  - herencia (los campos del padre van primero, así un hijo se puede usar como su padre),
 *  - vtable de cada clase (índice de cada método; un @Override reemplaza la entrada del padre).
 *
 * Disposición de un objeto de clase:   +0 puntero a vtable | +4... campos
 * Disposición de una estructura:       +0... campos (sin vtable)
 */
public class ClassLayouts {

    public static final class Campo {
        public final String nombre;
        public final ResolvedType tipo;
        public final List<Integer> dims;
        public int offset;
        public int size;

        Campo(String nombre, ResolvedType tipo, List<Integer> dims) {
            this.nombre = nombre;
            this.tipo = tipo;
            this.dims = dims == null ? Collections.emptyList() : dims;
        }

        public boolean esArreglo() { return !dims.isEmpty(); }
        public boolean esDoble() { return !esArreglo() && tipo.tipo() == Type.FLOTANTE; }
    }

    public static final class TipoUsr {
        public final String nombre;
        public final boolean esClase;
        public String padre;
        public final LinkedHashMap<String, Campo> campos = new LinkedHashMap<>();
        public int fin;
        public int tam;
        public final List<String> vnombres = new ArrayList<>();
        public final Map<String, String> vimpl = new HashMap<>();
        boolean vtableLista = false;

        TipoUsr(String nombre, boolean esClase) {
            this.nombre = nombre;
            this.esClase = esClase;
        }
    }

    private final Map<String, Map<String, ResolvedType>> estructuras;
    private final Map<String, Map<String, ResolvedType>> clases;
    private final Map<String, Map<String, List<Integer>>> dimensiones;
    private final Map<String, String> superclases;
    private final Map<String, TipoUsr> tipos = new LinkedHashMap<>();

    public ClassLayouts(Map<String, Map<String, ResolvedType>> estructuras,
                        Map<String, Map<String, ResolvedType>> clases,
                        Map<String, Map<String, List<Integer>>> dimensiones,
                        Map<String, String> superclases) {
        this.estructuras = estructuras;
        this.clases = clases;
        this.dimensiones = dimensiones;
        this.superclases = superclases;
    }

    /** @param nombresFunciones nombres de todas las funciones generadas (de ahí salen los métodos de cada clase) */
    public void construir(Collection<String> nombresFunciones) {
        tipos.clear();
        for (String n : estructuras.keySet()) if (!clases.containsKey(n)) construirTipo(n, false);
        for (String n : clases.keySet()) construirTipo(n, true);
        construirVtables(nombresFunciones);
    }

    public TipoUsr get(String nombre) { return nombre == null ? null : tipos.get(nombre); }
    public Collection<TipoUsr> todos() { return tipos.values(); }

    // ------------------------------------------------------------------
    // Campos
    // ------------------------------------------------------------------
    private TipoUsr construirTipo(String nombre, boolean esClase) {
        TipoUsr existente = tipos.get(nombre);
        if (existente != null) return existente;
        TipoUsr t = new TipoUsr(nombre, esClase);
        tipos.put(nombre, t);
        int cursor = esClase ? 4 : 0;
        if (esClase) {
            String padre = superclases.get(nombre);
            if (padre != null && clases.containsKey(padre) && !padre.equals(nombre)) {
                TipoUsr p = construirTipo(padre, true);
                t.padre = padre;
                t.campos.putAll(p.campos);
                cursor = p.fin;
            }
        }
        Map<String, ResolvedType> propios = esClase ? clases.get(nombre) : estructuras.get(nombre);
        Map<String, List<Integer>> dimsTipo = dimensiones.get(nombre);
        if (propios != null) {
            for (Map.Entry<String, ResolvedType> e : propios.entrySet()) {
                List<Integer> dims = dimsTipo == null ? null : dimsTipo.get(e.getKey());
                Campo c = new Campo(e.getKey(), e.getValue(), dims);
                c.size = c.esDoble() ? 8 : 4;
                cursor = FrameContext.alinear(cursor, c.size);
                c.offset = cursor;
                cursor += c.size;
                t.campos.put(c.nombre, c);
            }
        }
        t.fin = cursor;
        t.tam = Math.max(8, FrameContext.alinear(cursor, 8));
        return t;
    }

    /** Busca el campo en el tipo dado; si no se conoce el tipo, en el único tipo que lo declare. */
    public Campo campoDe(String tipoNombre, String campo) {
        if (tipoNombre != null) {
            TipoUsr t = tipos.get(tipoNombre);
            if (t != null && t.campos.containsKey(campo)) return t.campos.get(campo);
        }
        Campo unico = null;
        int veces = 0;
        for (TipoUsr t : tipos.values()) {
            Campo c = t.campos.get(campo);
            if (c != null) { unico = c; veces++; }
        }
        return veces == 1 ? unico : null;
    }

    // ------------------------------------------------------------------
    // Métodos y vtables
    // ------------------------------------------------------------------
    /** "Persona_saludar" -> "Persona"; "calcularArea" -> null. Elige el nombre de clase más largo que coincida. */
    public String claseDeFuncion(String nombreFunc) {
        String mejor = null;
        for (TipoUsr t : tipos.values()) {
            if (!t.esClase) continue;
            if (nombreFunc.startsWith(t.nombre + "_") && (mejor == null || t.nombre.length() > mejor.length())) mejor = t.nombre;
        }
        return mejor;
    }

    public static boolean esCtor(String resto) { return resto.equals("init") || resto.matches("init_\\d+"); }

    private void construirVtables(Collection<String> nombresFunciones) {
        Map<String, List<String>> propios = new LinkedHashMap<>();
        for (String f : nombresFunciones) {
            String clase = claseDeFuncion(f);
            if (clase == null) continue;
            String resto = f.substring(clase.length() + 1);
            if (!esCtor(resto)) propios.computeIfAbsent(clase, k -> new ArrayList<>()).add(resto);
        }
        for (TipoUsr t : tipos.values()) if (t.esClase) vtable(t, propios);
    }

    private void vtable(TipoUsr t, Map<String, List<String>> propios) {
        if (t.vtableLista) return;
        t.vtableLista = true;
        if (t.padre != null) {
            TipoUsr p = tipos.get(t.padre);
            vtable(p, propios);
            t.vnombres.addAll(p.vnombres);
            t.vimpl.putAll(p.vimpl);
        }
        for (String m : propios.getOrDefault(t.nombre, Collections.emptyList())) {
            if (!t.vnombres.contains(m)) t.vnombres.add(m);   // método nuevo: entrada nueva
            t.vimpl.put(m, t.nombre + "_" + m);               // @Override: reemplaza la del padre
        }
    }

    // ------------------------------------------------------------------
    // Reporte (para la pestaña "memoria" y el manual técnico)
    // ------------------------------------------------------------------
    public String reporte() {
        StringBuilder r = new StringBuilder("=== HEAP: disposición de objetos ===\n");
        for (TipoUsr t : tipos.values()) {
            r.append(t.esClase ? "clase " : "estructura ").append(t.nombre);
            if (t.padre != null) r.append(" extends ").append(t.padre);
            r.append("  (tamaño ").append(t.tam).append(" bytes)\n");
            if (t.esClase) r.append("  +0  vtable -> vt_").append(t.nombre).append("\n");
            for (Campo c : t.campos.values()) {
                r.append("  +").append(c.offset).append("  ").append(c.nombre).append("  (")
                        .append(c.tipo.tipoUsuario() != null ? c.tipo.tipoUsuario() : c.tipo.tipo())
                        .append(c.esArreglo() ? "[]" : "").append(")\n");
            }
            if (t.esClase) {
                for (int i = 0; i < t.vnombres.size(); i++) {
                    r.append("  vtable[").append(i).append("] ").append(t.vnombres.get(i))
                            .append(" -> ").append(t.vimpl.get(t.vnombres.get(i))).append("\n");
                }
            }
        }
        return r.toString();
    }
}
