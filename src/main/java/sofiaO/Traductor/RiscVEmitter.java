package sofiaO.Traductor;

import sofiaO.Traductor.riscv.ClassLayouts;
import sofiaO.Traductor.riscv.FrameContext;
import sofiaO.Traductor.riscv.FrameContext.Firma;
import sofiaO.Traductor.riscv.emitters.RvEmitterRegistry;
import sofiaO.semantico.ResolvedType;
import sofiaO.util.Type;
import sofiaO.util.TypeMapper;

import java.util.*;

/**
 * Fase 2: traduce las cuartetas (Quadruple) a ensamblador RISC-V (RV32IMFD, sintaxis RARS).
 * Es solo el orquestador; el trabajo está repartido así:
 *
 *   riscv/ClassLayouts      -> disposición de objetos en el HEAP, herencia y vtables
 *   riscv/FrameContext      -> marco de PILA de la función en curso y acceso a variables/literales
 *   riscv/RiscVInline       -> alloc (sbrk), cadenas y conversiones EXPANDIDAS EN LÍNEA (sin funciones auxiliares)
 *   riscv/emitters/*        -> un emisor por familia de operación (control, aritmética, cadenas,
 *                              llamadas, objetos, arreglos, E/S) + RvEmitterRegistry
 *
 * Modelo de memoria: variables, parámetros y temporales en la PILA (un marco por llamada);
 * objetos, arreglos y cadenas nuevas en el HEAP (sbrk). Detalle del marco en FrameContext.
 */
public class RiscVEmitter {

    // ------------------------------------------------------------------
    // Entrada (se configura igual que CEmitter)
    // ------------------------------------------------------------------
    public Map<String, String> tiposUsuarioDeLugares = new LinkedHashMap<>();
    private Map<String, Map<String, ResolvedType>> layoutsEstructuras = new LinkedHashMap<>();
    private Map<String, Map<String, ResolvedType>> layoutsClases = new LinkedHashMap<>();
    private Map<String, Map<String, List<Integer>>> dimensionesCampos = new LinkedHashMap<>();
    private Map<String, String> superclases = new LinkedHashMap<>();

    private String reporteMemoria = "";
    private final RvEmitterRegistry registro = new RvEmitterRegistry();

    public void setLayouts(Map<String, Map<String, ResolvedType>> estructuras,
                           Map<String, Map<String, ResolvedType>> clases) {
        this.layoutsEstructuras = estructuras == null ? new LinkedHashMap<>() : estructuras;
        this.layoutsClases = clases == null ? new LinkedHashMap<>() : clases;
    }

    public void setDimensionesCampos(Map<String, Map<String, List<Integer>>> dims) {
        this.dimensionesCampos = dims == null ? new LinkedHashMap<>() : dims;
    }

    /** clase hija -> clase padre (herencia). Puede estar vacío. */
    public void setSuperclases(Map<String, String> superclases) {
        this.superclases = superclases == null ? new LinkedHashMap<>() : superclases;
    }

    /** Tabla de memoria de la última emisión (marcos de pila, layout de objetos, vtables). */
    public String getReporteMemoria() { return reporteMemoria; }

    private static final class Func {
        final String nombre; final String retornoTxt;
        final List<Quadruple> params = new ArrayList<>();
        final List<Quadruple> cuerpo = new ArrayList<>();
        Func(String nombre, String retornoTxt) { this.nombre = nombre; this.retornoTxt = retornoTxt; }
    }

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------
    public String emitir(List<Quadruple> cuartetas) {
        // 1. separar funciones y las instrucciones globales (las de Pig Latin corren al inicio de main)
        List<Func> funcs = new ArrayList<>();
        List<Quadruple> globales = new ArrayList<>();
        Func actual = null;
        for (Quadruple q : cuartetas) {
            switch (q.op) {
                case "func" -> { actual = new Func(q.result, q.arg1); funcs.add(actual); }
                case "endfunc" -> actual = null;
                case "param" -> { if (actual != null) actual.params.add(q); }
                case "halt" -> { }
                default -> { if (actual != null) actual.cuerpo.add(q); else globales.add(q); }
            }
        }

        // 2. disposición de objetos, vtables y firmas
        ClassLayouts layouts = new ClassLayouts(layoutsEstructuras, layoutsClases, dimensionesCampos, superclases);
        List<String> nombres = new ArrayList<>();
        for (Func f : funcs) nombres.add(f.nombre);
        layouts.construir(nombres);

        FrameContext ctx = new FrameContext(layouts, tiposUsuarioDeLugares);
        for (Func f : funcs) ctx.firmas.put(f.nombre, firmaDe(f, layouts));

        // 3. código de cada función. main va primero: el programa arranca en la primera instrucción.
        StringBuilder texto = new StringBuilder();
        texto.append(".text\n.globl main\n");
        if (!nombres.contains("main")) texto.append("main:\n    li a7, 10\n    ecall\n\n");   // solo bibliotecas: nada que ejecutar
        List<Func> ordenadas = new ArrayList<>();
        for (Func f : funcs) if (f.nombre.equals("main")) ordenadas.add(f);
        for (Func f : funcs) if (!f.nombre.equals("main")) ordenadas.add(f);

        for (Func f : ordenadas) {
            ctx.iniciarFuncion(f.nombre, f.params);
            if (f.nombre.equals("main")) for (Quadruple q : globales) registro.emitir(q, ctx);
            for (Quadruple q : f.cuerpo) registro.emitir(q, ctx);
            texto.append(ctx.terminarFuncion());
        }

        // 4. armar el archivo: .data (literales y vtables) + .text
        StringBuilder salida = new StringBuilder();
        salida.append("# ==========================================================================\n");
        salida.append("# Código RISC-V (RV32IMFD) generado por Extraterrestre3D - Proyecto 2\n");
        salida.append("# Para RARS. Memoria MANUAL: pila y heap son bloques estáticos de .data con control de desborde (sin sbrk ni pila del sistema). Un marco por función (s0 = fp, ra en -4(s0)). Sin funciones auxiliares.\n");
        salida.append("# ==========================================================================\n");
        salida.append(".data\n");
        salida.append("str_null:   .asciz \"null\"\n");
        salida.append("str_true:   .asciz \"true\"\n");
        salida.append("str_false:  .asciz \"false\"\n");
        salida.append("str_nullerr: .asciz \"Error: referencia nula (NullPointerException)\\n\"\n");
        salida.append("str_heaperr: .asciz \"Error: memoria agotada (el heap estatico se lleno)\\n\"\n");
        salida.append("str_negerr: .asciz \"Error: tamano de arreglo negativo\\n\"\n");
        salida.append("str_stackerr: .asciz \"Error: desbordamiento de pila (recursion demasiado profunda)\\n\"\n");
        for (Map.Entry<String, String> e : ctx.cadenas.entrySet()) {
            salida.append(e.getValue()).append(": .asciz \"").append(e.getKey()).append("\"\n");
        }
        if (!ctx.dobles.isEmpty()) salida.append(".align 3\n");
        for (Map.Entry<String, String> e : ctx.dobles.entrySet()) {
            salida.append(e.getValue()).append(": .double ").append(e.getKey()).append("\n");
        }
        for (ClassLayouts.TipoUsr t : layouts.todos()) {
            if (!t.esClase) continue;
            salida.append("vt_").append(t.nombre).append(": .word ");
            if (t.vnombres.isEmpty()) salida.append("0");
            else {
                StringJoiner sj = new StringJoiner(", ");
                for (String m : t.vnombres) sj.add(FrameContext.lblFunc(t.vimpl.get(m)));
                salida.append(sj);
            }
            salida.append("\n");
        }
        // Memoria estática: un bloque para el heap (puntero que solo avanza) y el límite de la pila.
        salida.append(".align 3\n");
        salida.append("heap_ptr: .word heap_mem\n");
        salida.append("stack_lim: .word 0\n");
        salida.append(".align 3\n");
        salida.append("heap_mem: .space ").append(sofiaO.Traductor.riscv.RiscVInline.TAM_HEAP).append("\n");
        salida.append("heap_fin:\n");
        salida.append(".align 4\n");
        salida.append("stack_mem: .space ").append(sofiaO.Traductor.riscv.RiscVInline.TAM_PILA).append("\n");
        salida.append("stack_fin:\n");
        // Las rutinas de error se escriben UNA vez (no son funciones del programa: solo avisan y terminan).
        texto.append("error_heap:\n    la a0, str_heaperr\n    li a7, 4\n    ecall\n    li a7, 10\n    ecall\n\n");
        texto.append("error_negativo:\n    la a0, str_negerr\n    li a7, 4\n    ecall\n    li a7, 10\n    ecall\n\n");
        texto.append("error_pila:\n    la a0, str_stackerr\n    li a7, 4\n    ecall\n    li a7, 10\n    ecall\n\n");
        salida.append("\n").append(texto);

        reporteMemoria = "=== PILA: marcos de cada función ===\n" + ctx.reportePila + "\n" + layouts.reporte();
        return salida.toString();
    }

    // ------------------------------------------------------------------
    // Firmas (qué parámetros son double y qué retorna cada función)
    // ------------------------------------------------------------------
    private Firma firmaDe(Func f, ClassLayouts layouts) {
        Firma fi = new Firma();
        for (Quadruple p : f.params) fi.dobles.add(FrameContext.paramEsDoble(p));
        fi.retorno = tipoDeTexto(f.retornoTxt, layouts);
        return fi;
    }

    private Type tipoDeTexto(String texto, ClassLayouts layouts) {
        if (texto == null || texto.equals("void")) return Type.VOID;
        Type t = TypeMapper.deTexto(texto);
        if (t != Type.ERROR) return t;
        ClassLayouts.TipoUsr u = layouts.get(texto);
        if (u != null) return u.esClase ? Type.CLASE : Type.ESTRUCTURA;
        return Type.ENTERO;
    }
}
