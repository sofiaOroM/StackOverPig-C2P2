package sofiaO.ast.declaracion;

import sofiaO.ast.ASTVisitor;
import sofiaO.ast.BaseNode;
import sofiaO.ast.Declaration;
import sofiaO.ast.Statement;

import java.util.List;

/** public Persona(String nombreParametro, int edadParametro) { ... } (Zetariano) */
public class ConstructorDeclNode extends BaseNode implements Declaration {
    public String claseDuena;
    public List<ParamNode> parametros;
    public List<Statement> cuerpo;
    /** Encapsulamiento del constructor: un constructor private solo puede invocarse desde su propia clase. */
    public Visibilidad visibilidad = Visibilidad.PUBLIC;
    /**
     * Argumentos de un super(...) explícito al inicio del constructor; null si no lo hay
     * (en una clase hija eso significa llamada implícita al constructor sin parámetros del padre).
     */
    public java.util.List<sofiaO.ast.Expression> argumentosSuper;
    public int lineaSuper;
    public int columnaSuper;

    public ConstructorDeclNode(String claseDuena, List<ParamNode> parametros, List<Statement> cuerpo,
                               int line, int column) {
        super(line, column);
        this.claseDuena = claseDuena;
        this.parametros = parametros;
        this.cuerpo = cuerpo;
    }

    @Override
    public <T> T accept(ASTVisitor<T> v) { return v.visit(this); }
}
