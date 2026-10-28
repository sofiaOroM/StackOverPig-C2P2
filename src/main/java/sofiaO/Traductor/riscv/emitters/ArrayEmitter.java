package sofiaO.Traductor.riscv.emitters;

import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.riscv.ClassLayouts.TipoUsr;
import sofiaO.Traductor.riscv.FrameContext;
import sofiaO.Traductor.riscv.FrameContext.Elem;
import sofiaO.Traductor.riscv.FrameContext.Ref;
import sofiaO.Traductor.riscv.RiscVInline;
import sofiaO.util.Type;

import java.util.List;
import java.util.Set;

/**
 * Arreglos en el HEAP (aplanados): newarray, getindex, setindex.
 * Tamaño de elemento: 4 bytes (entero, bool, char, punteros), 8 (double) o el tamaño de la estructura
 * cuando los elementos son estructuras (van en línea y getindex devuelve su dirección).
 */
public class ArrayEmitter implements RvInstructionEmitter {

    @Override
    public Set<String> operaciones() { return Set.of("newarray", "getindex", "setindex", "arraydim"); }

    @Override
    public void emitir(Quadruple q, FrameContext f) {
        switch (q.op) {
            case "newarray" -> emitirNewArray(q, f);
            case "getindex" -> emitirGetIndex(q, f);
            case "arraydim" -> emitirArrayDim(q, f);
            default -> emitirSetIndex(q, f);
        }
    }

    private void emitirNewArray(Quadruple q, FrameContext f) {
        Ref r = f.ref(q.result);
        int size;
        if (q.tipoResultado == Type.ESTRUCTURA) {
            String usr = r.tipoUsr != null ? r.tipoUsr : f.tiposUsuarioDeLugares.get(q.result);
            TipoUsr t = f.layouts.get(usr);
            if (t == null) throw new IllegalStateException("Arreglo de estructura desconocida: " + usr);
            size = t.tam;
        } else if (q.tipoResultado == Type.FLOTANTE) size = 8;
        else size = 4;
        // Encabezado de 32 bytes ANTES de los datos (el puntero apunta a los datos):
        //   -4 rank | -8 total | -12 d0 | -16 d1 | -20 d2      (los tamaños pueden conocerse solo al ejecutar)
        List<String> dims = q.extraArgs.isEmpty() ? List.of(q.arg1) : q.extraArgs;
        // un tamaño negativo no es válido: se avisa en lugar de reservar memoria con un tamaño absurdo
        for (String d : dims) {
            f.cargarPalabra(d, "t1");
            String ok = f.nuevaAux();
            f.ins("bgez t1, " + ok);
            f.ins("j error_negativo");
            f.etiqueta(ok);
        }
        f.cargarPalabra(q.arg1, "t0");
        f.ins("li t1, " + size);
        f.ins("mul a0, t0, t1");
        f.ins("addi a0, a0, " + ENCABEZADO);
        RiscVInline.alloc(f);
        f.ins("li t1, " + dims.size());
        f.ins("sw t1, " + (ENCABEZADO - 4) + "(a0)");
        f.cargarPalabra(q.arg1, "t1");
        f.ins("sw t1, " + (ENCABEZADO - 8) + "(a0)");
        for (int k = 0; k < 3; k++) {
            if (k < dims.size()) f.cargarPalabra(dims.get(k), "t1"); else f.ins("li t1, 0");
            f.ins("sw t1, " + (ENCABEZADO - 12 - 4 * k) + "(a0)");
        }
        f.ins("addi a0, a0, " + ENCABEZADO);
        f.marcarPuntero(r);                 // antes de guardar: un arreglo de double NO es un double
        f.guardarPalabraEn(q.result, "a0");
    }

    /** Tamaño en bytes del encabezado que antecede a los datos de todo arreglo. */
    public static final int ENCABEZADO = 32;

    /** Reserva un arreglo de tamaño constante (con encabezado) y deja el puntero a los datos en a0. */
    public static void reservarConstante(FrameContext f, List<Integer> dims, int elemSize) {
        long total = 1;
        for (int d : dims) total *= Math.max(d, 0);
        f.ins("li a0, " + (total * elemSize + ENCABEZADO));
        RiscVInline.alloc(f);
        f.ins("li t1, " + dims.size());
        f.ins("sw t1, " + (ENCABEZADO - 4) + "(a0)");
        f.ins("li t1, " + total);
        f.ins("sw t1, " + (ENCABEZADO - 8) + "(a0)");
        for (int k = 0; k < 3; k++) {
            f.ins("li t1, " + (k < dims.size() ? Math.max(dims.get(k), 0) : 0));
            f.ins("sw t1, " + (ENCABEZADO - 12 - 4 * k) + "(a0)");
        }
        f.ins("addi a0, a0, " + ENCABEZADO);
    }

    /** resultado = tamaño de la dimensión k del arreglo (leído del encabezado). */
    private void emitirArrayDim(Quadruple q, FrameContext f) {
        int k = Integer.parseInt(q.arg2);
        f.cargarPalabra(q.arg1, "t5");
        f.comprobarNulo("t5");
        f.ins("lw t2, " + (-12 - 4 * k) + "(t5)");
        f.guardarPalabraEn(q.result, "t2");
    }

    private Elem elemDe(FrameContext f, String arreglo) {
        Ref r = f.ref(arreglo);
        if (r.tipo == Type.ESTRUCTURA) {
            TipoUsr t = f.layouts.get(r.tipoUsr != null ? r.tipoUsr : f.tiposUsuarioDeLugares.get(arreglo));
            if (t == null) throw new IllegalStateException("No se conoce la estructura de los elementos de " + arreglo);
            return new Elem(t.tam, true, false);
        }
        if (r.tipo == Type.FLOTANTE) return new Elem(8, false, true);
        return new Elem(4, false, false);
    }

    /** Deja en t5 la dirección de arreglo[indice]. */
    private void direccionElemento(FrameContext f, String arreglo, String indice, int size) {
        f.cargarPalabra(indice, "t1");
        f.cargarPalabra(arreglo, "t5");
        f.comprobarNulo("t5");
        if (size == 4) f.ins("slli t1, t1, 2");
        else if (size == 8) f.ins("slli t1, t1, 3");
        else { f.ins("li t2, " + size); f.ins("mul t1, t1, t2"); }
        f.ins("add t5, t5, t1");
    }

    private void emitirGetIndex(Quadruple q, FrameContext f) {
        Elem e = elemDe(f, q.arg1);
        direccionElemento(f, q.arg1, q.arg2, e.size());
        if (e.enLinea()) { f.ins("mv t2, t5"); f.guardarPalabraEn(q.result, "t2"); }
        else if (e.doble()) { f.ins("fld ft2, 0(t5)"); f.guardarDobleEn(q.result, "ft2"); }
        else { f.ins("lw t2, 0(t5)"); f.guardarPalabraEn(q.result, "t2"); }
    }

    private void emitirSetIndex(Quadruple q, FrameContext f) {
        Elem e = elemDe(f, q.arg1);
        if (e.enLinea()) throw new IllegalStateException("No se puede asignar una estructura completa a un elemento de arreglo");
        if (e.doble()) f.cargarDoble(q.result, "ft0");
        else if (f.esDobleVal(q.result)) { f.cargarDoble(q.result, "ft0"); f.ins("fcvt.w.d t0, ft0"); }
        else f.cargarPalabra(q.result, "t0");
        direccionElemento(f, q.arg1, q.arg2, e.size());
        f.ins(e.doble() ? "fsd ft0, 0(t5)" : "sw t0, 0(t5)");
    }
}
