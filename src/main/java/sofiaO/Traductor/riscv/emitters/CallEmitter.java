package sofiaO.Traductor.riscv.emitters;

import sofiaO.Traductor.Quadruple;
import sofiaO.Traductor.riscv.ClassLayouts;
import sofiaO.Traductor.riscv.FrameContext;
import sofiaO.Traductor.riscv.FrameContext.Asignacion;
import sofiaO.Traductor.riscv.FrameContext.Firma;
import sofiaO.util.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Llamadas a función y a método (convención RISC-V sin optimizar):
 *   - argumentos en a0-a7; un double ocupa dos registros (parte alta, parte baja); los que no caben van a la pila
 *   - retorno en a0 (entero/puntero) o en a0:a1 (double: alta, baja)
 *   - jal ra, func_X   (el llamado guarda ra y el fp en su propio marco)
 * Un método de clase se despacha por la vtable del objeto (polimorfismo); constructores y
 * funciones libres se llaman directo.
 */
public class CallEmitter implements RvInstructionEmitter {

    @Override
    public Set<String> operaciones() { return Set.of("call"); }

    @Override
    public void emitir(Quadruple q, FrameContext f) {
        String fname = q.arg1;
        List<String> args = q.extraArgs;
        Firma firma = f.buscarFirma(fname);
        int n = args.size();

        List<Boolean> dobles = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dobles.add(firma != null && i < firma.dobles.size() ? firma.dobles.get(i) : f.esDobleVal(args.get(i)));
        }
        Asignacion[] asig = FrameContext.asignarArgumentos(dobles);

        int enPila = 0;
        for (Asignacion a : asig) if (!a.enRegistros()) enPila++;
        int bytesPila = FrameContext.alinear(8 * enPila, 16);
        if (bytesPila > 0) f.ajustarSp(-bytesPila);

        for (int i = 0; i < n; i++) {
            String a = args.get(i);
            Asignacion as = asig[i];
            if (as.enRegistros()) {
                if (dobles.get(i)) {                                   // double -> par de registros (alta, baja)
                    f.cargarDoble(a, "ft0");
                    f.mem("fsd", "ft0", "s0", f.scratch());
                    f.mem("lw", "a" + as.primerRegistro(), "s0", f.scratch() + 4);
                    f.mem("lw", "a" + (as.primerRegistro() + 1), "s0", f.scratch());
                } else if (f.esDobleVal(a)) {                          // double pasado a un parámetro entero
                    f.cargarDoble(a, "ft0");
                    f.ins("fcvt.w.d a" + as.primerRegistro() + ", ft0");
                } else {
                    f.cargarPalabra(a, "a" + as.primerRegistro());
                }
            } else {                                                   // no cupo en registros: a la pila
                if (dobles.get(i)) { f.cargarDoble(a, "ft0"); f.ins("fsd ft0, " + (8 * as.indicePila()) + "(sp)"); }
                else {
                    if (f.esDobleVal(a)) { f.cargarDoble(a, "ft0"); f.ins("fcvt.w.d t0, ft0"); }
                    else f.cargarPalabra(a, "t0");
                    f.ins("sw t0, " + (8 * as.indicePila()) + "(sp)");
                }
            }
        }

        // super.metodo() se marca como llamada directa: va a esa versión, no a la del objeto en tiempo de ejecución
        int slot = Quadruple.LLAMADA_DIRECTA.equals(q.arg2) ? -1 : indiceVirtual(f, fname);
        if (slot >= 0) {
            f.comprobarNulo("a0");              // a0 = receptor (this)
            f.ins("lw t0, 0(a0)                # vtable del objeto");
            f.mem("lw", "t0", "t0", 4 * slot);  // método real según la clase del objeto
            f.ins("jalr ra, t0, 0");
        } else {
            f.ins("jal ra, " + FrameContext.lblFunc(fname));
        }
        if (bytesPila > 0) f.ajustarSp(bytesPila);

        if (q.result != null) {
            Type ret = firma != null ? firma.retorno : q.tipoResultado;
            if (ret == Type.VOID) return;
            if (ret == Type.FLOTANTE) {                                // a0 = alta, a1 = baja
                f.mem("sw", "a0", "s0", f.scratch() + 4);
                f.mem("sw", "a1", "s0", f.scratch());
                f.mem("fld", "ft2", "s0", f.scratch());
                f.guardarDobleEn(q.result, "ft2");
            } else {
                f.guardarPalabraEn(q.result, "a0");
            }
        }
    }

    /** Índice en la vtable si es un método de clase; -1 si es una función libre o un constructor. */
    private int indiceVirtual(FrameContext f, String fname) {
        String clase = f.layouts.claseDeFuncion(fname);
        if (clase == null) return -1;
        String resto = fname.substring(clase.length() + 1);
        ClassLayouts.TipoUsr t = f.layouts.get(clase);
        if (ClassLayouts.esCtor(resto) || !t.vimpl.containsKey(resto)) return -1;
        return t.vnombres.indexOf(resto);
    }
}
