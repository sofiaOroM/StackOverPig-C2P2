package sofiaO.Traductor.riscv.emitters;

import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.riscv.FrameContext;
import sofiaO.util.Type;

import java.util.Set;

/** Operadores aritméticos, relacionales y lógicos (binarios y unarios), en enteros y en double. */
public class ArithmeticEmitter implements RvInstructionEmitter {

    private static final Set<String> COMPARACIONES = Set.of("==", "!=", "<", ">", "<=", ">=");

    @Override
    public Set<String> operaciones() {
        return Set.of("+", "-", "*", "/", "%", "==", "!=", "<", ">", "<=", ">=", "&&", "||", "!");
    }

    @Override
    public void emitir(Quadruple q, FrameContext f) {
        if (q.arg2 == null) emitirUnaria(q, f); else emitirBinaria(q, f);
    }

    private void emitirBinaria(Quadruple q, FrameContext f) {
        String op = q.op;
        boolean cmp = COMPARACIONES.contains(op);
        boolean dbl = f.esDobleVal(q.arg1) || f.esDobleVal(q.arg2) || (!cmp && q.tipoResultado == Type.FLOTANTE);
        if (dbl) {
            f.cargarDoble(q.arg1, "ft0");
            f.cargarDoble(q.arg2, "ft1");
            switch (op) {
                case "+" -> f.ins("fadd.d ft2, ft0, ft1");
                case "-" -> f.ins("fsub.d ft2, ft0, ft1");
                case "*" -> f.ins("fmul.d ft2, ft0, ft1");
                case "/" -> f.ins("fdiv.d ft2, ft0, ft1");
                case "==" -> f.ins("feq.d t2, ft0, ft1");
                case "!=" -> { f.ins("feq.d t2, ft0, ft1"); f.ins("xori t2, t2, 1"); }
                case "<" -> f.ins("flt.d t2, ft0, ft1");
                case ">" -> f.ins("flt.d t2, ft1, ft0");
                case "<=" -> f.ins("fle.d t2, ft0, ft1");
                case ">=" -> f.ins("fle.d t2, ft1, ft0");
                default -> throw new IllegalStateException("Operador no soportado para double: " + op);
            }
            if (cmp) f.guardarPalabraEn(q.result, "t2"); else f.guardarDobleEn(q.result, "ft2");
            return;
        }
        f.cargarPalabra(q.arg1, "t0");
        f.cargarPalabra(q.arg2, "t1");
        if ((op.equals("==") || op.equals("!=")) && f.tipoVal(q.arg1) == Type.CADENA && f.tipoVal(q.arg2) == Type.CADENA) {
            sofiaO.Traductor.riscv.RiscVInline.compararCadenas(f);   // contenido, no direccion
            if (op.equals("!=")) f.ins("xori t2, t2, 1");
            f.guardarPalabraEn(q.result, "t2");
            return;
        }
        switch (op) {
            case "+" -> f.ins("add t2, t0, t1");
            case "-" -> f.ins("sub t2, t0, t1");
            case "*" -> f.ins("mul t2, t0, t1");
            case "/" -> f.ins("div t2, t0, t1");
            case "%" -> f.ins("rem t2, t0, t1");
            case "==" -> { f.ins("sub t2, t0, t1"); f.ins("seqz t2, t2"); }
            case "!=" -> { f.ins("sub t2, t0, t1"); f.ins("snez t2, t2"); }
            case "<" -> f.ins("slt t2, t0, t1");
            case ">" -> f.ins("slt t2, t1, t0");
            case "<=" -> { f.ins("slt t2, t1, t0"); f.ins("xori t2, t2, 1"); }
            case ">=" -> { f.ins("slt t2, t0, t1"); f.ins("xori t2, t2, 1"); }
            case "&&" -> { f.ins("snez t0, t0"); f.ins("snez t1, t1"); f.ins("and t2, t0, t1"); }
            case "||" -> { f.ins("or t2, t0, t1"); f.ins("snez t2, t2"); }
            default -> throw new IllegalStateException("Operación desconocida: " + q);
        }
        f.guardarPalabraEn(q.result, "t2");
    }

    private void emitirUnaria(Quadruple q, FrameContext f) {
        switch (q.op) {
            case "-" -> {
                if (f.esDobleVal(q.arg1) || q.tipoResultado == Type.FLOTANTE) {
                    f.cargarDoble(q.arg1, "ft0"); f.ins("fneg.d ft2, ft0"); f.guardarDobleEn(q.result, "ft2");
                } else {
                    f.cargarPalabra(q.arg1, "t0"); f.ins("neg t2, t0"); f.guardarPalabraEn(q.result, "t2");
                }
            }
            case "!" -> { f.cargarPalabra(q.arg1, "t0"); f.ins("seqz t2, t0"); f.guardarPalabraEn(q.result, "t2"); }
            case "+" -> f.asignar(q.result, q.arg1);
            default -> throw new IllegalStateException("Operación unaria desconocida: " + q);
        }
    }
}
