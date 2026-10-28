package sofiaO.Traductor.riscv;

/**
 * Operaciones de apoyo EXPANDIDAS EN LÍNEA: el código generado no llama a ninguna función auxiliar
 * (concatenar, convertir a cadena, reservar memoria...). Cada vez que una cuarteta lo necesita, se
 * escribe la secuencia completa de instrucciones en ese punto, con etiquetas únicas.
 *
 * Convención de estas expansiones (todo vive en el marco, nada queda en registros entre cuartetas,
 * así que pueden usar libremente t0-t6, s1-s9, a0-a7 y ft0-ft7; s0 es el frame pointer y no se toca):
 *     entrada:  a0 (y a1 / fa0 según la operación)         salida: a0
 */
public final class RiscVInline {

    private RiscVInline() {}

    /** Escribe una plantilla reemplazando @ por un identificador único (etiquetas) y S1/S2 por registros. */
    private static void emitir(FrameContext f, String plantilla, String... reemplazos) {
        String id = f.nuevaAux().substring(3);      // "__a17" -> "17"
        String texto = plantilla.replace("@", "_" + id);     // etiquetas: ok_17, cic_17 ... (nunca a1, a2...)
        for (int i = 0; i + 1 < reemplazos.length; i += 2) texto = texto.replace(reemplazos[i], reemplazos[i + 1]);
        for (String linea : texto.split("\n")) {
            if (linea.isBlank()) continue;
            if (linea.endsWith(":") && !linea.startsWith(" ")) f.etiqueta(linea.substring(0, linea.length() - 1));
            else f.ins(linea.trim());
        }
    }

    /** Tamaño del heap estático (bloque reservado en .data). Se reparte con un puntero que solo avanza. */
    public static final int TAM_HEAP = 1 << 20;     // 1 MiB
    /** Máximo de pila que se permite usar al programa antes de avisar de un desbordamiento. */
    public static final int TAM_PILA = 1 << 18;     // 256 KiB

    // ------------------------------------------------------------------
    // HEAP ESTÁTICO (sin memoria dinámica del sistema: nada de sbrk / ecall 9).
    //   a0 = bytes  ->  a0 = dirección (alineada a 8) dentro del bloque heap_mem.
    //   heap_ptr guarda el siguiente byte libre. Si lo pedido no cabe (o el tamaño es absurdo/negativo)
    //   el programa se detiene con un mensaje en lugar de pisar otra zona de memoria.
    //   Usa solo t4, t5, t6 y a0.
    // ------------------------------------------------------------------
    public static void alloc(FrameContext f) {
        f.ins("addi a0, a0, 7");
        f.ins("andi a0, a0, -8");
        emitir(f, """
            la t6, heap_ptr
            lw t5, 0(t6)
            add t4, t5, a0
            bltu t4, t5, hov@
            la a0, heap_fin
            bleu t4, a0, hok@
            hov@:
            j error_heap
            hok@:
            sw t4, 0(t6)
            mv a0, t5
            """);
    }

    // ------------------------------------------------------------------
    // Referencia nula: si 'reg' es 0 imprime el error y termina
    // ------------------------------------------------------------------
    public static void comprobarNulo(FrameContext f, String reg) {
        emitir(f, """
            bnez REG, ok@
            la a0, str_nullerr
            li a7, 4
            ecall
            li a7, 10
            ecall
            ok@:
            """, "REG", reg);
    }

    // ------------------------------------------------------------------
    // Imprimir una cadena (a0 = puntero; null imprime "null")
    // ------------------------------------------------------------------
    public static void imprimirCadena(FrameContext f) {
        emitir(f, """
            bnez a0, ok@
            la a0, str_null
            ok@:
            li a7, 4
            ecall
            """);
    }

    public static void boolACadena(FrameContext f) {
        emitir(f, """
            bnez a0, v@
            la a0, str_false
            j fin@
            v@:
            la a0, str_true
            fin@:
            """);
    }

    public static void charACadena(FrameContext f) {
        f.ins("mv s1, a0");
        f.ins("li a0, 8");
        alloc(f);
        f.ins("sb s1, 0(a0)");
        f.ins("sb zero, 1(a0)");
    }

    // ------------------------------------------------------------------
    // a0 = entero  ->  a0 = cadena nueva en el heap
    // ------------------------------------------------------------------
    public static void enteroACadena(FrameContext f) { enteroACadena(f, "s1", "s2"); }

    /** Variante con otros registros de trabajo (para usarla dentro de doubleACadena). */
    private static void enteroACadena(FrameContext f, String r1, String r2) {
        f.ins("mv " + r1 + ", a0");
        f.ins("li a0, 16");
        alloc(f);
        f.ins("mv " + r2 + ", a0");
        emitir(f, """
            addi t0, R2, 15
            sb zero, 0(t0)
            mv t1, R1
            li t2, 0
            bgez t1, pos@
            li t2, 1
            neg t1, t1
            pos@:
            li t3, 10
            bnez t1, cic@
            addi t0, t0, -1
            li t4, 48
            sb t4, 0(t0)
            j sig@
            cic@:
            beqz t1, sig@
            remu t4, t1, t3
            divu t1, t1, t3
            addi t4, t4, 48
            addi t0, t0, -1
            sb t4, 0(t0)
            j cic@
            sig@:
            beqz t2, fin@
            addi t0, t0, -1
            li t4, 45
            sb t4, 0(t0)
            fin@:
            mv a0, t0
            """, "R1", r1, "R2", r2);
    }

    // ------------------------------------------------------------------
    // a0 = cadena A, a1 = cadena B  ->  a0 = cadena nueva (null se trata como "null")
    // ------------------------------------------------------------------
    public static void concatenar(FrameContext f) {
        emitir(f, """
            bnez a0, a@
            la a0, str_null
            a@:
            bnez a1, b@
            la a1, str_null
            b@:
            mv s1, a0
            mv s2, a1
            li s3, 0
            mv t0, s1
            la@:
            lbu t1, 0(t0)
            beqz t1, lae@
            addi t0, t0, 1
            addi s3, s3, 1
            j la@
            lae@:
            li s4, 0
            mv t0, s2
            lb@:
            lbu t1, 0(t0)
            beqz t1, lbe@
            addi t0, t0, 1
            addi s4, s4, 1
            j lb@
            lbe@:
            add a0, s3, s4
            addi a0, a0, 1
            """);
        alloc(f);                                  // a0 = bloque nuevo (heap estático, con control de desborde)
        emitir(f, """
            mv s3, a0
            mv t3, a0
            mv t0, s1
            ca@:
            lbu t1, 0(t0)
            beqz t1, cb@
            sb t1, 0(t3)
            addi t0, t0, 1
            addi t3, t3, 1
            j ca@
            cb@:
            mv t0, s2
            cc@:
            lbu t1, 0(t0)
            sb t1, 0(t3)
            beqz t1, fin@
            addi t0, t0, 1
            addi t3, t3, 1
            j cc@
            fin@:
            mv a0, s3
            """);
    }

    // ------------------------------------------------------------------
    // fa0 = double  ->  a0 = cadena nueva. 6 cifras significativas sin ceros finales (como printf %g)
    // ------------------------------------------------------------------
    public static void dobleACadena(FrameContext f) {
        f.ins("fcvt.d.w ft1, zero");
        f.ins("flt.d s3, fa0, ft1");
        emitir(f, """
            beqz s3, abs@
            fneg.d fa0, fa0
            abs@:
            fmv.d ft5, fa0
            """);
        piso(f);                                   // a0 = parte entera
        emitir(f, """
            mv s1, a0
            fcvt.d.w ft2, s1
            fsub.d ft3, ft5, ft2
            li s6, 0
            bnez s1, ent@
            li t1, 1
            fcvt.d.w ft6, t1
            li t1, 10
            fcvt.d.w ft7, t1
            fdiv.d ft6, ft6, ft7
            fmv.d ft4, ft3
            li t2, 0
            cer@:
            li t1, 3
            bge t2, t1, cerf@
            flt.d t0, ft4, ft6
            beqz t0, cerf@
            fmul.d ft4, ft4, ft7
            addi t2, t2, 1
            j cer@
            cerf@:
            addi s6, t2, 6
            j esc@
            ent@:
            mv t0, s1
            li t2, 0
            li t3, 10
            dig@:
            beqz t0, digf@
            divu t0, t0, t3
            addi t2, t2, 1
            j dig@
            digf@:
            li s6, 6
            sub s6, s6, t2
            bgez s6, esc@
            li s6, 0
            esc@:
            li s7, 1
            li t0, 0
            li t3, 10
            pot@:
            bge t0, s6, potf@
            mul s7, s7, t3
            addi t0, t0, 1
            j pot@
            potf@:
            fcvt.d.w ft4, s7
            fmul.d ft3, ft3, ft4
            li t1, 1
            fcvt.d.w ft6, t1
            li t1, 2
            fcvt.d.w ft7, t1
            fdiv.d ft6, ft6, ft7
            fadd.d fa0, ft3, ft6
            """);
        piso(f);                                   // a0 = dígitos decimales como entero
        emitir(f, """
            mv s2, a0
            blt s2, s7, sa@
            sub s2, s2, s7
            addi s1, s1, 1
            sa@:
            or t0, s1, s2
            bnez t0, sg@
            li s3, 0
            sg@:
            mv a0, s1
            """);
        enteroACadena(f, "s8", "s9");              // a0 = parte entera como texto
        f.ins("mv s5, a0");
        f.ins("li a0, 48");
        alloc(f);
        emitir(f, """
            mv s4, a0
            mv t3, s4
            beqz s3, ent2@
            li t0, 45
            sb t0, 0(t3)
            addi t3, t3, 1
            ent2@:
            mv t0, s5
            cp@:
            lbu t1, 0(t0)
            beqz t1, fr@
            sb t1, 0(t3)
            addi t0, t0, 1
            addi t3, t3, 1
            j cp@
            fr@:
            beqz s2, cie@
            li t0, 46
            sb t0, 0(t3)
            addi t3, t3, 1
            li t5, 10
            divu t4, s7, t5
            mv t2, s6
            dg@:
            divu t0, s2, t4
            remu s2, s2, t4
            addi t0, t0, 48
            sb t0, 0(t3)
            addi t3, t3, 1
            divu t4, t4, t5
            addi t2, t2, -1
            bnez t2, dg@
            rec@:
            lbu t0, -1(t3)
            li t1, 48
            bne t0, t1, cie@
            addi t3, t3, -1
            j rec@
            cie@:
            sb zero, 0(t3)
            mv a0, s4
            """);
    }

    /** fa0 (>= 0) -> a0 = parte entera (piso). Usa ft0 y t0. */
    private static void piso(FrameContext f) {
        emitir(f, """
            fcvt.w.d a0, fa0
            fcvt.d.w ft0, a0
            fle.d t0, ft0, fa0
            bnez t0, ok@
            addi a0, a0, -1
            ok@:
            """);
    }

    // ------------------------------------------------------------------
    // Leer una línea (ecall 8) y quitarle el salto de línea  ->  a0 = cadena nueva
    // ------------------------------------------------------------------
    /** Compara el CONTENIDO de dos cadenas (punteros en t0 y t1). Deja en t2 1 si son iguales, 0 si no. null solo iguala a null. */
    public static void compararCadenas(FrameContext f) {
        emitir(f, """
            beq t0, t1, ig@
            beqz t0, di@
            beqz t1, di@
            lp@:
            lbu t3, 0(t0)
            lbu t4, 0(t1)
            bne t3, t4, di@
            beqz t3, ig@
            addi t0, t0, 1
            addi t1, t1, 1
            j lp@
            ig@:
            li t2, 1
            j fn@
            di@:
            li t2, 0
            fn@:
            """);
    }

    public static void leerCadena(FrameContext f) {
        f.ins("li a0, 256");
        alloc(f);
        emitir(f, """
            mv s1, a0
            li a1, 256
            li a7, 8
            ecall
            mv t0, s1
            ci@:
            lbu t1, 0(t0)
            beqz t1, fin@
            li t2, 10
            beq t1, t2, cor@
            addi t0, t0, 1
            j ci@
            cor@:
            sb zero, 0(t0)
            fin@:
            mv a0, s1
            """);
    }
}
