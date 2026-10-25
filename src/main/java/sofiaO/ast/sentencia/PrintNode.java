package sofiaO.ast.sentencia;

import sofiaO.ast.ASTVisitor;
import sofiaO.ast.BaseNode;
import sofiaO.ast.Expression;
import sofiaO.ast.Statement;

import java.util.List;

/** imprimir("...") (Y?) / println/print(...) (Zetariano) / >> a >> b; variádico (Pig Latin) */
public class PrintNode extends BaseNode implements Statement {
    public List<Expression> argumentos; // uno o más
    /** false en print(...) de Zetariano (no agrega salto de línea); true en println, imprimir y >> */
    public boolean saltoLinea = true;

    public PrintNode(List<Expression> argumentos, int line, int column) {
        super(line, column);
        this.argumentos = argumentos;
    }

    @Override
    public <T> T accept(ASTVisitor<T> v) { return v.visit(this); }
}
