package sofiaO.ast.expresion;

import sofiaO.ast.ASTVisitor;
import sofiaO.ast.BaseNode;
import sofiaO.ast.Expression;

import java.util.List;

/**
 * new int[5]  /  new char[tamanio]  /  new boolean[filas][columnas]   (Zetariano)
 * Los tamaños son expresiones cualquiera (no solo constantes): se evalúan al ejecutar.
 */
public class NewArrayNode extends BaseNode implements Expression {
    /** Tipo de los elementos tal como se escribió: int, char, boolean, double, String o una clase. */
    public String tipoElemento;
    public List<Expression> dimensiones;

    public NewArrayNode(String tipoElemento, List<Expression> dimensiones, int line, int column) {
        super(line, column);
        this.tipoElemento = tipoElemento;
        this.dimensiones = dimensiones;
    }

    @Override
    public <T> T accept(ASTVisitor<T> v) { return v.visit(this); }
}
