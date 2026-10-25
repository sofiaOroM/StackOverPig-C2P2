package sofiaO.resaltado;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Coloreado del ensamblador RISC-V generado (sintaxis RARS). Igual que el resto del resaltado, está
 * escrito a mano y recorre el texto una sola vez:
 *   # comentario          directivas (.data, .text, .word ...)       "cadenas"
 *   mnemónicos (add, lw)   registros (t0, sp, fa0)                    etiquetas (main:, f_suma, __alloc)
 */
final class ResaltadorRiscV {

    private ResaltadorRiscV() {}

    private static final Set<String> MNEMONICOS = Set.of(
            "add", "addi", "sub", "mul", "mulh", "div", "divu", "rem", "remu",
            "and", "andi", "or", "ori", "xor", "xori", "sll", "slli", "srl", "srli", "sra", "srai",
            "slt", "slti", "sltu", "sltiu", "lui", "auipc",
            "lw", "lh", "lhu", "lb", "lbu", "sw", "sh", "sb",
            "fld", "fsd", "flw", "fsw",
            "fadd.d", "fsub.d", "fmul.d", "fdiv.d", "fneg.d", "fabs.d", "fmv.d", "fsqrt.d",
            "feq.d", "flt.d", "fle.d", "fcvt.d.w", "fcvt.w.d", "fcvt.d.s", "fcvt.s.d",
            "beq", "bne", "blt", "bge", "bltu", "bgeu", "beqz", "bnez", "bgez", "blez", "bgtz", "bltz",
            "j", "jal", "jalr", "jr", "ret", "li", "la", "mv", "neg", "not", "seqz", "snez", "nop", "ecall");

    private static final Set<String> REGISTROS = new java.util.HashSet<>(Set.of(
            "zero", "ra", "sp", "gp", "tp", "fp", "s0", "s1"));

    static {
        for (int i = 0; i <= 31; i++) { REGISTROS.add("x" + i); REGISTROS.add("f" + i); }
        for (int i = 0; i <= 6; i++) REGISTROS.add("t" + i);
        for (int i = 2; i <= 11; i++) REGISTROS.add("s" + i);
        for (int i = 0; i <= 7; i++) { REGISTROS.add("a" + i); REGISTROS.add("fa" + i); }
        for (int i = 0; i <= 11; i++) { REGISTROS.add("ft" + i); REGISTROS.add("fs" + i); }
    }

    static List<Segmento> resaltar(String texto) {
        List<Segmento> salida = new ArrayList<>();
        int n = texto.length();
        int i = 0;
        while (i < n) {
            char c = texto.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }

            if (c == '#') {                                   // comentario hasta fin de línea
                int fin = finDeLinea(texto, i);
                salida.add(new Segmento(i, fin, Estilos.COMENTARIO));
                i = fin;
                continue;
            }
            if (c == '"') {                                   // cadena de .asciz
                int fin = i + 1;
                while (fin < n && texto.charAt(fin) != '"' && texto.charAt(fin) != '\n') {
                    fin += texto.charAt(fin) == '\\' ? 2 : 1;
                }
                fin = Math.min(n, fin + 1);
                salida.add(new Segmento(i, fin, Estilos.CADENA));
                i = fin;
                continue;
            }
            if (c == '.' && i + 1 < n && Character.isLetter(texto.charAt(i + 1))) {   // directiva
                int fin = i + 1;
                while (fin < n && Character.isLetterOrDigit(texto.charAt(fin))) fin++;
                salida.add(new Segmento(i, fin, Estilos.SECCION));
                i = fin;
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(texto.charAt(i + 1)))) {
                int fin = i + 1;
                while (fin < n && (Character.isLetterOrDigit(texto.charAt(fin)) || texto.charAt(fin) == '.')) fin++;
                salida.add(new Segmento(i, fin, Estilos.NUMERO));
                i = fin;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int fin = i;
                while (fin < n && (Character.isLetterOrDigit(texto.charAt(fin))
                        || texto.charAt(fin) == '_' || texto.charAt(fin) == '.' || texto.charAt(fin) == '$')) fin++;
                String palabra = texto.substring(i, fin);
                String estilo;
                if (fin < n && texto.charAt(fin) == ':') estilo = Estilos.ETIQUETA;          // definición de etiqueta
                else if (MNEMONICOS.contains(palabra)) estilo = Estilos.PALABRA_CLAVE;
                else if (REGISTROS.contains(palabra)) estilo = Estilos.TIPO;
                else estilo = Estilos.FUNCION;                                                // etiqueta usada como operando
                salida.add(new Segmento(i, fin, estilo));
                i = fin;
                continue;
            }
            i++;
        }
        return salida;
    }

    private static int finDeLinea(String texto, int desde) {
        int fin = texto.indexOf('\n', desde);
        if (fin < 0) return texto.length();
        return fin > desde && texto.charAt(fin - 1) == '\r' ? fin - 1 : fin;
    }
}
