package sofiaO.Traductor.riscv.emitters;

import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.riscv.FrameContext;
import sofiaO.Traductor.riscv.RiscVInline;
import sofiaO.util.Type;

import java.util.Set;

/** Entrada y salida estándar mediante ecall (print: 1,4,11,34; read: 5,7,8,12). */
public class IoEmitter implements RvInstructionEmitter {

    @Override
    public Set<String> operaciones() { return Set.of("print", "read"); }

    @Override
    public void emitir(Quadruple q, FrameContext f) {
        if (q.op.equals("print")) emitirPrint(q, f); else emitirRead(q, f);
    }

    private void emitirPrint(Quadruple q, FrameContext f) {
        for (int i = 0; i < q.extraArgs.size(); i++) {
            String a = q.extraArgs.get(i);
            Type declarado = i < q.tiposExtra.size() ? q.tiposExtra.get(i) : Type.ERROR;
            Type real = f.tipoVal(a);
            if (real == Type.ERROR) real = declarado;
            if (FrameContext.esCadenaLit(a)) {
                f.ins("la a0, " + f.etiquetaCadena(a)); f.ins("li a7, 4"); f.ins("ecall");
            } else if (f.esDobleVal(a)) {
                f.cargarDoble(a, "fa0"); RiscVInline.dobleACadena(f); RiscVInline.imprimirCadena(f);
            } else if (real == Type.CADENA) {
                f.cargarPalabra(a, "a0"); RiscVInline.imprimirCadena(f);
            } else if (real == Type.CARACTER) {
                f.cargarPalabra(a, "a0"); f.ins("li a7, 11"); f.ins("ecall");
            } else if (real == Type.CLASE || real == Type.ESTRUCTURA || real == Type.NULO) {
                f.cargarPalabra(a, "a0"); f.ins("li a7, 34"); f.ins("ecall");
            } else {
                f.cargarPalabra(a, "a0"); f.ins("li a7, 1"); f.ins("ecall");
            }
        }
        if (!"sin_salto".equals(q.arg1)) { f.ins("li a0, 10"); f.ins("li a7, 11"); f.ins("ecall"); }   // salto de línea
    }

    private void emitirRead(Quadruple q, FrameContext f) {
        Type t = q.tipoResultado;
        if (t == Type.CADENA) { RiscVInline.leerCadena(f); f.guardarPalabraEn(q.result, "a0"); }
        else if (t == Type.FLOTANTE) { f.ins("li a7, 7"); f.ins("ecall"); f.guardarDobleEn(q.result, "fa0"); }
        else if (t == Type.CARACTER) {
            // como scanf(" %c") del C: salta espacios y saltos de línea que quedaron de la lectura anterior
            String repetir = f.nuevaAux();
            f.etiqueta(repetir);
            f.ins("li a7, 12"); f.ins("ecall");
            f.ins("li t0, 32"); f.ins("beq a0, t0, " + repetir);
            f.ins("li t0, 10"); f.ins("beq a0, t0, " + repetir);
            f.ins("li t0, 13"); f.ins("beq a0, t0, " + repetir);
            f.guardarPalabraEn(q.result, "a0");
        }
        else { f.ins("li a7, 5"); f.ins("ecall"); f.guardarPalabraEn(q.result, "a0"); }
    }
}
