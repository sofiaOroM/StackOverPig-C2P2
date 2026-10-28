package sofiaO.ast.declaracion;

import sofiaO.ast.ASTVisitor;
import sofiaO.ast.BaseNode;
import sofiaO.ast.Declaration;
import sofiaO.ast.Statement;

import java.util.List;

/** definir f(...): ... (Y?) / método dentro de una clase (Zetariano) / MAIOR> (Pig Latin) */
public class FunctionDeclNode extends BaseNode implements Declaration {
    public String nombre;
    public List<ParamNode> parametros;
    public String tipoRetorno;   // null si no retorna nada
    public List<Statement> cuerpo;
    public boolean esMetodo;
    public String claseDuena;    // nombre de la clase si esMetodo == true; null si no
    /** Encapsulamiento del método (Zetariano). Las funciones libres (Y?) y los MAIOR> (Pig Latin) son PUBLIC. */
    public Visibilidad visibilidad = Visibilidad.PUBLIC;
    /** true si el método lleva @Override (obligatorio al sobrescribir un método de una superclase). */
    public boolean esOverride = false;

    public FunctionDeclNode(String nombre, List<ParamNode> parametros, String tipoRetorno,
                            List<Statement> cuerpo, boolean esMetodo, String claseDuena,
                            int line, int column) {
        super(line, column);
        this.nombre = nombre;
        this.parametros = parametros;
        this.tipoRetorno = tipoRetorno;
        this.cuerpo = cuerpo;
        this.esMetodo = esMetodo;
        this.claseDuena = claseDuena;
    }

    /** Tipo de retorno sin los corchetes: "int[][]" -> "int". Un retorno de arreglo devuelve el puntero al arreglo. */
    public String tipoRetornoBase() {
        return tipoRetorno == null ? null : tipoRetorno.replace("[]", "").trim();
    }

    /** true si el método devuelve un arreglo (int[], String[], int[][]...). */
    public boolean retornaArreglo() {
        return tipoRetorno != null && tipoRetorno.contains("[]");
    }

    /**
     * Nombre del método en el código generado. Distingue las sobrecargas y hace que un @Override
     * (misma firma en la clase hija) tenga el mismo nombre que el método del padre:
     *   saludar()               -> saludar
     *   insertarFinal(int)      -> insertarFinal_int
     *   insertarFinal(int,int)  -> insertarFinal_int_int
     * Las funciones libres (Y?) conservan su nombre.
     */
    public String nombreCodigo() {
        if (!esMetodo || parametros.isEmpty()) return nombre;
        StringBuilder sb = new StringBuilder(nombre);
        for (ParamNode p : parametros) {
            String t = p.tipo.replace("[]", "Arr");
            if (p.modo == ParamNode.ModoPaso.REFERENCIA_ARREGLO && !p.tipo.contains("[]")) t += "Arr";
            sb.append('_').append(t);
        }
        return sb.toString();
    }

    @Override
    public <T> T accept(ASTVisitor<T> v) { return v.visit(this); }
}
