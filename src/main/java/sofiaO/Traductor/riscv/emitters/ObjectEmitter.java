package sofiaO.Traductor.riscv.emitters;

import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.riscv.ClassLayouts.Campo;
import sofiaO.Traductor.riscv.ClassLayouts.TipoUsr;
import sofiaO.Traductor.riscv.FrameContext;
import sofiaO.Traductor.riscv.RiscVInline;
import sofiaO.util.Type;

import java.util.Set;

/** Objetos y estructuras en el HEAP: new, lectura y escritura de campos. */
public class ObjectEmitter implements RvInstructionEmitter {

    @Override
    public Set<String> operaciones() { return Set.of("new", "getfield", "setfield"); }

    @Override
    public void emitir(Quadruple q, FrameContext f) {
        switch (q.op) {
            case "new" -> emitirNew(q, f);
            case "getfield" -> emitirGetField(q, f);
            default -> emitirSetField(q, f);
        }
    }

    private void emitirNew(Quadruple q, FrameContext f) {
        TipoUsr t = f.layouts.get(q.arg1);
        if (t == null) throw new IllegalStateException("Tipo desconocido en 'new': " + q.arg1);
        f.ins("li a0, " + t.tam);
        RiscVInline.alloc(f);
        if (t.esClase) { f.ins("la t0, vt_" + t.nombre); f.ins("sw t0, 0(a0)"); }   // vtable en el offset 0
        f.guardarPalabraEn(q.result, "a0");
        // arreglos de tamaño fijo dentro del objeto (los de estructuras los inicializa el generador)
        for (Campo c : t.campos.values()) {
            if (!c.esArreglo() || c.tipo.tipo() == Type.ESTRUCTURA) continue;
            long total = 1;
            for (int d : c.dims) total *= Math.max(d, 0);
            if (total <= 0) continue;
            ArrayEmitter.reservarConstante(f, c.dims, c.tipo.tipo() == Type.FLOTANTE ? 8 : 4);   // con encabezado
            f.cargarPalabra(q.result, "t0");
            f.mem("sw", "a0", "t0", c.offset);
        }
    }

    private void emitirGetField(Quadruple q, FrameContext f) {
        Campo c = f.layouts.campoDe(f.userTypeDe(q.arg1), q.arg2);
        if (c == null) throw new IllegalStateException("Campo desconocido '" + q.arg2 + "' en " + q);
        f.cargarPalabra(q.arg1, "t5");
        f.comprobarNulo("t5");
        if (c.esDoble()) { f.mem("fld", "ft2", "t5", c.offset); f.guardarDobleEn(q.result, "ft2"); }
        else {
            f.mem("lw", "t2", "t5", c.offset);
            if (c.esArreglo()) f.marcarPuntero(f.ref(q.result));   // antes de guardar (ver ArrayEmitter)
            f.guardarPalabraEn(q.result, "t2");
        }
    }

    private void emitirSetField(Quadruple q, FrameContext f) {
        Campo c = f.layouts.campoDe(f.userTypeDe(q.arg1), q.arg2);
        if (c == null) throw new IllegalStateException("Campo desconocido '" + q.arg2 + "' en " + q);
        if (c.esDoble()) f.cargarDoble(q.result, "ft0");
        else if (f.esDobleVal(q.result)) { f.cargarDoble(q.result, "ft0"); f.ins("fcvt.w.d t0, ft0"); }
        else f.cargarPalabra(q.result, "t0");
        f.cargarPalabra(q.arg1, "t5");
        f.comprobarNulo("t5");
        f.mem(c.esDoble() ? "fsd" : "sw", c.esDoble() ? "ft0" : "t0", "t5", c.offset);
    }
}
