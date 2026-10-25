package sofiaO.Traductor.riscv.emitters;

import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.riscv.FrameContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Junta todos los emisores y le sirve a RiscVEmitter el que corresponde a cada operación.
 * Para agregar una instrucción nueva: escribir (o ampliar) un emisor y registrarlo aquí.
 * Cualquier operación no registrada se trata como operador aritmético/lógico (+, -, ==, ...).
 */
public class RvEmitterRegistry {
    private final Map<String, RvInstructionEmitter> porOperacion = new HashMap<>();
    private final RvInstructionEmitter porDefecto = new ArithmeticEmitter();

    public RvEmitterRegistry() {
        registrar(new ControlEmitter());
        registrar(porDefecto);
        registrar(new StringEmitter());
        registrar(new CallEmitter());
        registrar(new ObjectEmitter());
        registrar(new ArrayEmitter());
        registrar(new IoEmitter());
    }

    private void registrar(RvInstructionEmitter e) {
        for (String op : e.operaciones()) porOperacion.put(op, e);
    }

    public void emitir(Quadruple q, FrameContext f) {
        if (!q.op.equals("func") && !q.op.equals("endfunc") && !q.op.equals("param") && !q.op.equals("halt")) {
            f.comentario(q.toString());          // cada cuarteta se deja como comentario sobre sus instrucciones
        }
        porOperacion.getOrDefault(q.op, porDefecto).emitir(q, f);
    }
}
