package sofiaO.ast.declaracion;

import sofiaO.ast.ASTVisitor;
import sofiaO.ast.BaseNode;
import sofiaO.ast.Declaration;

import java.util.List;

/** public class Persona { ... } (Zetariano) */
public class ClassDeclNode extends BaseNode implements Declaration {
    public String nombre;
    public List<FieldNode> atributos;
    public List<ConstructorDeclNode> constructores;
    public List<FunctionDeclNode> metodos;
    /** Nombre de la superclase ('extends'), o null si la clase no hereda de otra. */
    public String superclase;
    public int lineaSuperclase;
    public int columnaSuperclase;

    public ClassDeclNode(String nombre, List<FieldNode> atributos, List<ConstructorDeclNode> constructores,
                         List<FunctionDeclNode> metodos, int line, int column) {
        super(line, column);
        this.nombre = nombre;
        this.atributos = atributos;
        this.constructores = constructores;
        this.metodos = metodos;
    }

    @Override
    public <T> T accept(ASTVisitor<T> v) { return v.visit(this); }
}
