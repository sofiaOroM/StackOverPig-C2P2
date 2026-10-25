package sofiaO.Traductor.riscv.emitters;

import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.riscv.FrameContext;

import java.util.Set;

/** Traduce un grupo de operaciones de cuarteta a ensamblador RISC-V. No guarda estado: todo vive en FrameContext. */
public interface RvInstructionEmitter {
    /** Nombres de operación (Quadruple.op) que este emisor sabe traducir. */
    Set<String> operaciones();

    void emitir(Quadruple q, FrameContext f);
}
