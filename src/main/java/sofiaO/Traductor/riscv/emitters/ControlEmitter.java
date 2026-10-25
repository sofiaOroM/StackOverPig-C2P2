package sofiaO.Traductor.riscv.emitters;

import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.riscv.FrameContext;
import sofiaO.Traductor.riscv.FrameContext.Slot;
import sofiaO.util.Type;

import java.util.Set;

/** Declaraciones (ranuras de la PILA), asignación, etiquetas, saltos y return. */
public class ControlEmitter implements RvInstructionEmitter {

    @Override
    public Set<String> operaciones() {
        return Set.of("declare", "blockstart", "blockend", "label", "goto", "if_false", "if_true",
                "=", "return", "halt", "func", "endfunc", "param");
    }

    @Override
    public void emitir(Quadruple q, FrameContext f) {
        switch (q.op) {
            case "declare" -> {
                boolean arreglo = q.arg1 != null;
                String usr = q.arg2 != null ? q.arg2 : f.tiposUsuarioDeLugares.get(q.result);
                Slot s = f.nuevoSlot(q.result, q.tipoResultado, usr, arreglo);
                f.mem("sw", "zero", "s0", s.off);       // toda ranura nace en cero (null / 0)
                if (s.size == 8) f.mem("sw", "zero", "s0", s.off + 4);
            }
            case "blockstart" -> f.abrirScope();
            case "blockend" -> f.cerrarScope();
            case "label" -> f.etiqueta(q.result);
            case "goto" -> f.ins("j " + q.result);
            case "if_false" -> { f.cargarPalabra(q.arg1, "t0"); f.saltarSiCero("t0", q.result); }
            case "if_true" -> { f.cargarPalabra(q.arg1, "t0"); f.saltarSiNoCero("t0", q.result); }
            case "=" -> f.asignar(q.result, q.arg1);
            case "return" -> emitirReturn(q, f);
            default -> { /* halt, func, endfunc, param: no generan código */ }
        }
    }

    private void emitirReturn(Quadruple q, FrameContext f) {
        if (q.arg1 != null) {
            FrameContext.Firma firma = f.firmaActual();
            Type ret = firma != null ? firma.retorno : q.tipoResultado;
            if (ret == Type.FLOTANTE) {              // double: a0 = parte alta, a1 = parte baja
                f.cargarDoble(q.arg1, "ft0");
                f.mem("fsd", "ft0", "s0", f.scratch());
                f.mem("lw", "a0", "s0", f.scratch() + 4);
                f.mem("lw", "a1", "s0", f.scratch());
            }
            else if (f.esDobleVal(q.arg1)) { f.cargarDoble(q.arg1, "ft0"); f.ins("fcvt.w.d a0, ft0"); }
            else f.cargarPalabra(q.arg1, "a0");
        }
        f.ins("j " + f.etiquetaFin());
    }
}
