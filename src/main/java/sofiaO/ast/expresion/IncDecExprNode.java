package sofiaO.ast.expresion;

import sofiaO.ast.ASTVisitor;
import sofiaO.ast.BaseNode;
import sofiaO.ast.Expression;
import sofiaO.ast.Statement;

/**
 * ++k / k++ / --k / k-- usados COMO EXPRESIÓN (su valor se usa):  int a = ++k;  int b = k++;
 *  - prefijo : primero se actualiza la variable y el valor es el NUEVO.
 *  - postfijo: el valor es el ANTERIOR y luego se actualiza la variable.
 * La actualización (k = k + 1) se construye como una sentencia normal para reutilizar su análisis y su generación.
 */
public class IncDecExprNode extends BaseNode implements Expression {
    public Expression objetivo;       // lectura del valor (k, this.x, v[i])
    public Statement actualizacion;   // k++ / k = k + 1 (equivalente)
    public String operador;           // "++" o "--"
    public boolean prefijo;

    public IncDecExprNode(Expression objetivo, Statement actualizacion, String operador, boolean prefijo,
                          int line, int column) {
        super(line, column);
        this.objetivo = objetivo;
        this.actualizacion = actualizacion;
        this.operador = operador;
        this.prefijo = prefijo;
    }

    @Override
    public <T> T accept(ASTVisitor<T> v) { return v.visit(this); }
}
