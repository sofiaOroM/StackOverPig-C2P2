package sofiaO.Traductor.riscv;

import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.riscv.ClassLayouts.Campo;
import sofiaO.util.Type;

import java.util.*;

/**
 * Estado y utilidades de la función que se está emitiendo. Es lo único "con memoria" del
 * backend; los emisores de instrucciones (carpeta emitters/) no guardan nada por su cuenta.
 *
 * PILA: cada variable, parámetro y temporal tiene una ranura fija en el marco de la función.
 *
 *     8*i(s0)   argumento i (lo empuja quien llama)
 *     -4(s0)    ra guardado
 *     -8(s0)    s0 (fp) anterior
 *     -16(s0)   ranura 0,  -24(s0) ranura 1, ...      (8 bytes cada una)
 */
public class FrameContext {

    // ------------------------------------------------------------------
    // Tipos auxiliares
    // ------------------------------------------------------------------
    public static final class Firma {
        public final List<Boolean> dobles = new ArrayList<>();
        public Type retorno = Type.VOID;
    }

    public static final class Slot {
        public final String nombre;
        public int off;
        public final int size;          // 4 bytes (entero, char, bool, puntero) u 8 (double)
        public Type tipo;
        public String tipoUsr;
        public boolean puntero;

        Slot(String nombre, int off, int size, Type tipo, String tipoUsr, boolean puntero) {
            this.nombre = nombre; this.off = off; this.size = size; this.tipo = tipo; this.tipoUsr = tipoUsr; this.puntero = puntero;
        }
    }

    /** Dónde viaja un argumento: en los registros a0-a7 (un double ocupa dos) o, si ya no caben, en la pila. */
    public record Asignacion(boolean enRegistros, int primerRegistro, int indicePila) {}

    /**
     * Reparte los argumentos como lo hace el llamador y el llamado (deben coincidir):
     * enteros/punteros/char/bool -> un registro; double -> dos registros (primero la parte alta, luego la baja).
     * Cuando se agotan a0-a7, ese argumento y los siguientes van a la pila (8 bytes cada uno, desde 0(sp)).
     */
    public static Asignacion[] asignarArgumentos(List<Boolean> dobles) {
        Asignacion[] r = new Asignacion[dobles.size()];
        int palabras = 0, pila = 0;
        boolean agotado = false;
        for (int i = 0; i < dobles.size(); i++) {
            int necesita = dobles.get(i) ? 2 : 1;
            if (!agotado && palabras + necesita <= 8) { r[i] = new Asignacion(true, palabras, -1); palabras += necesita; }
            else { agotado = true; r[i] = new Asignacion(false, -1, pila++); }
        }
        return r;
    }

    /** Dónde vive un valor: una ranura del marco o un campo de 'this'. */
    public static final class Ref {
        public Type tipo = Type.ERROR;
        public String tipoUsr;
        public boolean puntero;
        public Slot slot;
        public Campo campo;

        public boolean doble() { return tipo == Type.FLOTANTE && !puntero; }
    }

    public record Elem(int size, boolean enLinea, boolean doble) {}

    // ------------------------------------------------------------------
    // Estado compartido de toda la emisión
    // ------------------------------------------------------------------
    public final ClassLayouts layouts;
    public final Map<String, String> tiposUsuarioDeLugares;
    public final Map<String, Firma> firmas = new HashMap<>();
    public final Map<String, String> cadenas = new LinkedHashMap<>();
    public final Map<String, String> dobles = new LinkedHashMap<>();
    public final StringBuilder reportePila = new StringBuilder();

    // ------------------------------------------------------------------
    // Estado de la función actual
    // ------------------------------------------------------------------
    private StringBuilder cod;
    private Deque<Map<String, Slot>> scopes;
    private int cursor;                 // offset (desde s0) del último byte usado del marco; crece hacia abajo
    private int scratchOff;
    private List<Slot> slotsParams;
    private List<Boolean> paramDobles;
    private List<String> mapaMarco;
    private String funcActual;
    private String claseActual;
    private int contAux;

    public FrameContext(ClassLayouts layouts, Map<String, String> tiposUsuarioDeLugares) {
        this.layouts = layouts;
        this.tiposUsuarioDeLugares = tiposUsuarioDeLugares;
    }

    public String funcActual() { return funcActual; }
    public String claseActual() { return claseActual; }
    public Firma firmaActual() { return firmas.get(funcActual); }
    public String etiquetaFin() { return lblFunc(funcActual) + "__fin"; }

    /** Ranura de 8 bytes del marco para pasar un double a/desde un par de registros enteros. */
    public int scratch() { return scratchOff; }

    public void comentario(String texto) { cod.append("    # ").append(texto.replace('\n', ' ')).append('\n'); }

    // ------------------------------------------------------------------
    // Inicio y cierre de una función
    // ------------------------------------------------------------------
    public static boolean paramEsArreglo(Quadruple p) { return p.arg2 != null && p.arg2.endsWith("[]"); }
    public static boolean paramEsDoble(Quadruple p) { return p.tipoResultado == Type.FLOTANTE && !paramEsArreglo(p); }

    public void iniciarFuncion(String nombre, List<Quadruple> params) {
        cod = new StringBuilder();
        scopes = new ArrayDeque<>();
        scopes.push(new HashMap<>());
        cursor = -4;                    // -4(s0) guarda ra; las variables empiezan en -8(s0)
        mapaMarco = new ArrayList<>();
        funcActual = nombre;
        claseActual = layouts.claseDeFuncion(nombre);
        slotsParams = new ArrayList<>();
        paramDobles = new ArrayList<>();

        // Los parámetros llegan en registros (a0-a7) y se guardan en el marco, como en cualquier función RISC-V sin optimizar.
        for (Quadruple p : params) {
            boolean arr = paramEsArreglo(p);
            String usr = p.arg1.equals("this") ? claseActual : tipoUsuarioDeParam(p);
            slotsParams.add(nuevoSlot(p.arg1, p.tipoResultado, usr, arr, "parámetro"));
            paramDobles.add(paramEsDoble(p));
        }
        scratchOff = nuevoSlot("__scratch", Type.FLOTANTE, null, false, "temporal para pasar doubles").off;
    }

    private String tipoUsuarioDeParam(Quadruple p) {
        String t = p.arg2;
        if (t != null) {
            if (t.endsWith("[]")) t = t.substring(0, t.length() - 2);
            if (layouts.get(t) != null) return t;
        }
        return tiposUsuarioDeLugares.get(p.arg1);
    }

    /** Devuelve la función completa: prólogo (con el tamaño de marco ya conocido), cuerpo y epílogo. */
    public String terminarFuncion() {
        int marco = alinear(8 - cursor, 16);
        boolean esMain = funcActual.equals("main");
        StringBuilder out = new StringBuilder();
        out.append("# ======================================================================\n");
        out.append("# ").append(funcActual).append(": marco de ").append(marco).append(" bytes\n");
        out.append("#   0(s0) = fp anterior    -4(s0) = ra    desde -8(s0): variables, parámetros y temporales\n");
        for (String m : mapaMarco) out.append("#   ").append(m).append("\n");
        out.append(lblFunc(funcActual)).append(":\n");
        if (esMain) {
            // PILA MANUAL: el programa no usa la pila del sistema. sp se coloca al final del bloque estático
            // stack_mem (reservado en .data) y el límite de pila es su comienzo.
            out.append("    la   sp, stack_fin         # pila propia (bloque estático): sp apunta a su tope\n");
            out.append("    la   t6, stack_mem\n    addi t6, t6, 256          # margen de seguridad\n");
            out.append("    la   t5, stack_lim\n    sw   t6, 0(t5)            # límite de la pila\n");
            out.append("    andi sp, sp, -16           # pila alineada a 16 bytes (los double necesitan alineación)\n");
        }
        // reservar el marco y COMPROBAR el límite antes de escribir nada en él
        String okPila = "pila_ok_" + lblFunc(funcActual);
        if (marco <= 2047) out.append("    addi sp, sp, -").append(marco).append("            # reserva el marco\n");
        else out.append("    li   t6, ").append(marco).append("\n    sub  sp, sp, t6            # reserva el marco\n");
        out.append("    la   t6, stack_lim\n    lw   t6, 0(t6)\n    bgeu sp, t6, ").append(okPila)
           .append("            # control de desbordamiento de pila\n    j    error_pila\n").append(okPila).append(":\n");
        if (marco <= 2047) {
            out.append("    sw   s0, ").append(marco - 8).append("(sp)            # guarda el fp anterior\n");
            out.append("    addi s0, sp, ").append(marco - 8).append("            # s0 = fp de esta función\n");
        } else {
            out.append("    li   t6, ").append(marco).append("\n");
            out.append("    add  t5, sp, t6\n    sw   s0, -8(t5)            # guarda el fp anterior\n");
            out.append("    addi s0, t5, -8            # s0 = fp de esta función\n");
        }
        out.append("    sw   ra, -4(s0)            # guarda ra\n");
        out.append(prologoParametros());
        out.append(cod);
        out.append(etiquetaFin()).append(":\n");
        if (esMain) {
            out.append("    li   a7, 10\n    ecall                      # fin del programa\n\n");
        } else {
            out.append("    lw   ra, -4(s0)            # restaura ra\n");
            out.append("    lw   s0, 0(s0)             # restaura el fp anterior\n");
            if (marco <= 2047) out.append("    addi sp, sp, ").append(marco).append("            # libera el marco\n");
            else out.append("    li   t6, ").append(marco).append("\n    add  sp, sp, t6            # libera el marco\n");
            out.append("    jr   ra                    # regresa al llamador\n\n");
        }
        reportePila.append("--- PILA: ").append(funcActual).append(" (marco ").append(marco).append(" bytes) ---\n");
        for (String m : mapaMarco) reportePila.append("  ").append(m).append("\n");
        return out.toString();
    }

    /** Guarda en el marco los argumentos recibidos en a0-a7 (o en la pila del llamador). */
    private String prologoParametros() {
        StringBuilder guardado = cod;
        cod = new StringBuilder();
        Asignacion[] asig = asignarArgumentos(paramDobles);
        for (int i = 0; i < slotsParams.size(); i++) {
            Slot s = slotsParams.get(i);
            Asignacion a = asig[i];
            comentario("parámetro " + s.nombre + (a.enRegistros()
                    ? (paramDobles.get(i) ? "  <- a" + a.primerRegistro() + ":a" + (a.primerRegistro() + 1) + " (alta:baja)" : "  <- a" + a.primerRegistro())
                    : "  <- pila del llamador"));
            if (a.enRegistros()) {
                if (paramDobles.get(i)) {
                    mem("sw", "a" + a.primerRegistro(), "s0", s.off + 4);       // parte alta
                    mem("sw", "a" + (a.primerRegistro() + 1), "s0", s.off);     // parte baja
                } else {
                    mem("sw", "a" + a.primerRegistro(), "s0", s.off);
                }
            } else {
                int desdeS0 = 8 + 8 * a.indicePila();                          // sp al entrar = s0 + 8
                if (paramDobles.get(i)) { mem("fld", "ft0", "s0", desdeS0); mem("fsd", "ft0", "s0", s.off); }
                else { mem("lw", "t0", "s0", desdeS0); mem("sw", "t0", "s0", s.off); }
            }
        }
        String texto = cod.toString();
        cod = guardado;
        return texto;
    }

    // ------------------------------------------------------------------
    // Emisión de texto
    // ------------------------------------------------------------------
    public void ins(String s) { cod.append("    ").append(s).append('\n'); }
    public void etiqueta(String nombre) { cod.append(nombre).append(":\n"); }

    /** lw/sw/fld/fsd con soporte para desplazamientos mayores a 12 bits. */
    public void mem(String op, String reg, String base, int off) {
        if (off >= -2048 && off <= 2047) ins(op + " " + reg + ", " + off + "(" + base + ")");
        else { ins("li t6, " + off); ins("add t6, " + base + ", t6"); ins(op + " " + reg + ", 0(t6)"); }
    }

    public void ajustarSp(int delta) {
        if (delta >= -2048 && delta <= 2047) ins("addi sp, sp, " + delta);
        else { ins("li t6, " + delta); ins("add sp, sp, t6"); }
    }

    public String nuevaAux() { return "__a" + (contAux++); }

    // Los saltos condicionales de RISC-V llegan a +-4 KiB; así se salta a cualquier distancia.
    public void saltarSiCero(String reg, String destino) {
        String k = nuevaAux();
        ins("bnez " + reg + ", " + k); ins("j " + destino); etiqueta(k);
    }

    public void saltarSiNoCero(String reg, String destino) {
        String k = nuevaAux();
        ins("beqz " + reg + ", " + k); ins("j " + destino); etiqueta(k);
    }

    /** Si 'reg' es 0 imprime el error de referencia nula y termina (expandido en línea). */
    public void comprobarNulo(String reg) { RiscVInline.comprobarNulo(this, reg); }

    public static String lblFunc(String nombre) { return nombre.equals("main") ? "main" : "func_" + nombre; }
    public static int alinear(int v, int a) { return (v + a - 1) / a * a; }

    // ------------------------------------------------------------------
    // Ranuras y resolución de lugares
    // ------------------------------------------------------------------
    public void abrirScope() { scopes.push(new HashMap<>()); }
    public void cerrarScope() { if (scopes.size() > 1) scopes.pop(); }

    private Slot buscarSlot(String nombre) {
        for (Map<String, Slot> s : scopes) {
            Slot x = s.get(nombre);
            if (x != null) return x;
        }
        return null;
    }

    public Slot nuevoSlot(String nombre, Type tipo, String usr, boolean arreglo) {
        return nuevoSlot(nombre, tipo, usr, arreglo, null);
    }

    /** Variables de 4 bytes (double: 8, alineado a 8). Crecen hacia abajo desde -8(s0). */
    private Slot nuevoSlot(String nombre, Type tipo, String usr, boolean arreglo, String nota) {
        Type t = tipo == null ? Type.ERROR : tipo;
        boolean doble = t == Type.FLOTANTE && !arreglo;
        int size = doble ? 8 : 4;
        int off = doble ? Math.floorDiv(cursor - 8, 8) * 8 : cursor - 4;
        cursor = off;
        Slot s = new Slot(nombre, off, size, t, usr, arreglo);
        scopes.peek().put(nombre, s);
        mapaMarco.add(String.format("%d(s0)  %s : %s%s", off, nombre, t, usr != null ? " (" + usr + ")" : "")
                + (arreglo ? "  [puntero al heap]" : "") + (nota != null ? "  <" + nota + ">" : ""));
        return s;
    }

    public Ref ref(String place) {
        Ref r = new Ref();
        if (place.startsWith("this->")) return refCampo(r, place.substring(6));
        Slot s = buscarSlot(place);
        if (s == null && claseActual != null) {
            ClassLayouts.TipoUsr cls = layouts.get(claseActual);
            if (cls != null && cls.campos.containsKey(place)) return refCampo(r, place);
        }
        if (s == null) s = nuevoSlot(place, Type.ERROR, tiposUsuarioDeLugares.get(place), false);
        r.slot = s; r.tipo = s.tipo; r.tipoUsr = s.tipoUsr; r.puntero = s.puntero;
        return r;
    }

    private Ref refCampo(Ref r, String nombre) {
        ClassLayouts.TipoUsr cls = layouts.get(claseActual);
        Campo c = cls == null ? null : cls.campos.get(nombre);
        if (c == null) throw new IllegalStateException("'" + nombre + "' no es un atributo de " + claseActual + " (en " + funcActual + ")");
        r.campo = c; r.tipo = c.tipo.tipo(); r.tipoUsr = c.tipo.tipoUsuario(); r.puntero = c.esArreglo();
        return r;
    }

    public void marcarPuntero(Ref r) { if (r.slot != null) { r.slot.puntero = true; } r.puntero = true; }

    public String userTypeDe(String place) {
        if (place.equals("this")) return claseActual;
        if (esLiteral(place)) return null;
        Ref r = ref(place);
        if (r.tipoUsr != null) return r.tipoUsr;
        return tiposUsuarioDeLugares.get(place);
    }

    public Firma buscarFirma(String nombre) {
        Firma f = firmas.get(nombre);
        if (f != null) return f;
        String clase = layouts.claseDeFuncion(nombre);
        if (clase != null) {
            String impl = layouts.get(clase).vimpl.get(nombre.substring(clase.length() + 1));
            if (impl != null) return firmas.get(impl);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Valores: literales, cargas y stores
    // ------------------------------------------------------------------
    public static boolean esCadenaLit(String v) { return v != null && v.startsWith("\""); }
    public static boolean esCharLit(String v) { return v != null && v.startsWith("'"); }
    public static boolean esNull(String v) { return "NULL".equals(v) || "null".equals(v); }
    public static boolean esEntero(String v) { return v != null && v.matches("-?\\d+"); }
    public static boolean esDecimal(String v) { return v != null && v.matches("-?\\d+\\.\\d+([eE][-+]?\\d+)?"); }
    public static boolean esLiteral(String v) {
        return esCadenaLit(v) || esCharLit(v) || esNull(v) || esEntero(v) || esDecimal(v);
    }

    public Type tipoVal(String v) {
        if (esCadenaLit(v)) return Type.CADENA;
        if (esCharLit(v)) return Type.CARACTER;
        if (esNull(v)) return Type.NULO;
        if (esEntero(v)) return Type.ENTERO;
        if (esDecimal(v)) return Type.FLOTANTE;
        return ref(v).tipo;
    }

    public boolean esDobleVal(String v) {
        if (v == null) return false;
        if (esDecimal(v)) return true;
        if (esLiteral(v)) return false;
        return ref(v).doble();
    }

    public boolean esArregloVal(String v) {
        if (esLiteral(v)) return false;
        Ref r = ref(v);
        return r.puntero && r.tipo != Type.CLASE && r.tipo != Type.ESTRUCTURA;
    }

    private void cargarThis(String reg) {
        Slot t = buscarSlot("this");
        if (t == null) throw new IllegalStateException("'this' no existe en " + funcActual);
        mem("lw", reg, "s0", t.off);
    }

    private void cargarDeRef(Ref r, String op, String reg) {
        if (r.slot != null) mem(op, reg, "s0", r.slot.off);
        else { cargarThis("t5"); mem(op, reg, "t5", r.campo.offset); }
    }

    private void guardarDeReg(Ref r, String op, String reg) {
        if (r.slot != null) mem(op, reg, "s0", r.slot.off);
        else { cargarThis("t5"); mem(op, reg, "t5", r.campo.offset); }
    }

    public void cargarPalabra(String v, String reg) {
        if (esNull(v)) { ins("li " + reg + ", 0"); return; }
        if (esCadenaLit(v)) { ins("la " + reg + ", " + etiquetaCadena(v)); return; }
        if (esCharLit(v)) { ins("li " + reg + ", " + valorChar(v)); return; }
        if (esEntero(v)) { ins("li " + reg + ", " + (int) Long.parseLong(v)); return; }
        if (esDecimal(v)) { ins("li " + reg + ", " + (int) Double.parseDouble(v)); return; }
        Ref r = ref(v);
        if (r.doble()) { cargarDeRef(r, "fld", "ft7"); ins("fcvt.w.d " + reg + ", ft7"); }
        else cargarDeRef(r, "lw", reg);
    }

    public void cargarDoble(String v, String freg) {
        if (esDecimal(v)) { ins("la t6, " + etiquetaDoble(v)); ins("fld " + freg + ", 0(t6)"); return; }
        if (esEntero(v) || esCharLit(v)) { cargarPalabra(v, "t6"); ins("fcvt.d.w " + freg + ", t6"); return; }
        Ref r = ref(v);
        if (r.doble()) cargarDeRef(r, "fld", freg);
        else { cargarDeRef(r, "lw", "t6"); ins("fcvt.d.w " + freg + ", t6"); }
    }

    /** Guarda un entero/puntero en 'place'; si el destino es double lo convierte. */
    public void guardarPalabraEn(String place, String reg) {
        Ref r = ref(place);
        if (r.doble()) { ins("fcvt.d.w ft7, " + reg); guardarDeReg(r, "fsd", "ft7"); }
        else guardarDeReg(r, "sw", reg);
    }

    /** Guarda un double en 'place'; si el destino es entero lo convierte. */
    public void guardarDobleEn(String place, String freg) {
        Ref r = ref(place);
        if (r.doble()) guardarDeReg(r, "fsd", freg);
        else { ins("fcvt.w.d t2, " + freg); guardarDeReg(r, "sw", "t2"); }
    }

    /** destino = origen, con las conversiones int<->double necesarias. */
    public void asignar(String destino, String origen) {
        Ref d = ref(destino);
        if (esArregloVal(origen)) {            // copiar el puntero de un arreglo
            cargarDeRef(ref(origen), "lw", "t0");
            guardarDeReg(d, "sw", "t0");
            marcarPuntero(d);
        } else if (d.doble()) {
            cargarDoble(origen, "ft0");
            guardarDeReg(d, "fsd", "ft0");
        } else {
            if (esDobleVal(origen)) { cargarDoble(origen, "ft0"); ins("fcvt.w.d t0, ft0"); }
            else cargarPalabra(origen, "t0");
            guardarDeReg(d, "sw", "t0");
        }
    }

    // ------------------------------------------------------------------
    // Literales en .data
    // ------------------------------------------------------------------
    public String etiquetaCadena(String literal) {
        String cuerpo = literal.substring(1, literal.length() - 1);
        String codificado = codificarCadena(cuerpo);
        return cadenas.computeIfAbsent(codificado, k -> "str_" + cadenas.size());
    }

    public String etiquetaDoble(String lit) {
        String norm = String.valueOf(Double.parseDouble(lit));
        return dobles.computeIfAbsent(norm, k -> "dbl_" + dobles.size());
    }

    private static String codificarCadena(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 128) { sb.append((char) cp); continue; }
            for (byte b : new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                sb.append('\\').append(String.format("%03o", b & 0xFF));
            }
        }
        return sb.toString();
    }

    private static int valorChar(String lit) {
        String c = lit.substring(1, lit.length() - 1);
        if (c.startsWith("\\") && c.length() >= 2) {
            return switch (c.charAt(1)) {
                case 'n' -> 10; case 't' -> 9; case 'r' -> 13; case '0' -> 0;
                case '\\' -> 92; case '\'' -> 39; case '"' -> 34;
                default -> c.charAt(1);
            };
        }
        return c.isEmpty() ? 0 : c.codePointAt(0);
    }
}
