package sofiaO.Traductor.riscv.emitters;

import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.riscv.FrameContext;
import sofiaO.Traductor.riscv.RiscVInline;

import java.util.Set;

/** Concatenación y conversiones a cadena. Toda cadena nueva se reserva en el HEAP (runtime). */
public class StringEmitter implements RvInstructionEmitter {

    @Override
    public Set<String> operaciones() {
        return Set.of("concat", "int_to_str", "char_to_str", "bool_to_str", "double_to_str");
    }

    @Override
    public void emitir(Quadruple q, FrameContext f) {
        switch (q.op) {
            case "concat" -> {
                f.cargarPalabra(q.arg1, "a0");
                f.cargarPalabra(q.arg2, "a1");
                RiscVInline.concatenar(f);
                f.guardarPalabraEn(q.result, "a0");
            }
            case "double_to_str" -> {
                f.cargarDoble(q.arg1, "fa0");
                RiscVInline.dobleACadena(f);
                f.guardarPalabraEn(q.result, "a0");
            }
            default -> {                       // int_to_str, char_to_str, bool_to_str
                f.cargarPalabra(q.arg1, "a0");
                switch (q.op) {
                    case "int_to_str" -> RiscVInline.enteroACadena(f);
                    case "char_to_str" -> RiscVInline.charACadena(f);
                    default -> RiscVInline.boolACadena(f);
                }
                f.guardarPalabraEn(q.result, "a0");
            }
        }
    }
}
