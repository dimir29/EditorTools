package ksp.kos.ideaplugin.dataflow;

import ksp.kos.ideaplugin.actions.differentiate.DiffContext;
import ksp.kos.ideaplugin.expressions.*;
import ksp.kos.ideaplugin.expressions.inline.InlineFunction;
import ksp.kos.ideaplugin.psi.*;
import ksp.kos.ideaplugin.reference.DualitySelfResolvable;
import ksp.kos.ideaplugin.reference.FlowSelfResolvable;
import ksp.kos.ideaplugin.reference.ReferableType;
import ksp.kos.ideaplugin.reference.Reference;
import ksp.kos.ideaplugin.reference.context.Duality;
import ksp.kos.ideaplugin.reference.context.FileContext;
import ksp.kos.ideaplugin.reference.context.LocalContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created on 12/03/16.
 *
 * @author ptasha
 */
public class FunctionFlow extends BaseFlow<FunctionFlow> implements NamedFlow<FunctionFlow>,
        ReferenceFlow<FunctionFlow>, Duality<KerboScriptNamedElement, FunctionFlow> {
    private final LocalContext context; // TODO replace file to FileContext, diff file context
    private final String name;

    private final ContextBuilder<ParameterFlow> parameters;
    private final ContextBuilder instructions;

    public FunctionFlow(LocalContext context, String name, ContextBuilder<ParameterFlow> parameters, ContextBuilder instructions) {
        this.context = context;
        this.name = name;
        this.parameters = parameters;
        parameters.buildMap();
        this.instructions = instructions;
        buildInstructionsMap();
    }

    private void buildInstructionsMap() {
        instructions.buildMap();
        instructions.getReturn().addDependee(this);
    }

    public static FunctionFlow parse(KerboScriptDeclareFunctionClause function) throws SyntaxException {
        FunctionParser parser = new FunctionParser();
        String name = function.getName();
        parser.parseInstructions(function.getInstructionBlock().getInstructionList());
        return new FunctionFlow(function.getKerboScriptFile().getCachedScope(), name, parser.getContext(), parser.getFlowContext());
        // throw new SyntaxException("Return statement is not found for function " + name); // TODO check for return statement
    }

    @Override
    public FunctionFlow differentiate(LocalContext context) {
        ContextBuilder parameters = new ContextBuilder();
        this.parameters.differentiate(context, parameters); // TODO single parameter

        ContextBuilder flows = new ContextBuilder(parameters);
        FunctionFlow diff = new FunctionFlow(context, name + "_", parameters, flows);
        context.register(diff);

        this.instructions.differentiate(context, flows);
        diff.buildInstructionsMap();
        diff.simplify();
        return diff;
    }

    private FunctionFlow simplify() {
        instructions.simplify();
        parameters.simplify(); // TODO teach Function to deal with it

        return this;
    }

    @Override
    public String getText() {
        String text = "";
        text += "function " + name + " {\n";
        text += parameters.getText() + "\n";
        if (!parameters.getList().isEmpty()) text += "\n";
        text += instructions.getText() + "\n";
        text += "}";
        return text;
    }

    @Override
    public LocalContext getKingdom() {
        return context;
    }

    @Override
    public ReferableType getReferableType() {
        return ReferableType.FUNCTION;
    }

    @Override
    public String getName() {
        return name;
    }

    public Set<ImportFlow> getImports() {
        HashSet<ImportFlow> imports = new HashSet<>();
        visitExpresssions(new ExpressionVisitor() {
            @Override
            public void visitFunction(Function function) {
                addImport(DualitySelfResolvable.function(context, function.getName()).findDeclaration());
                super.visitFunction(function);
            }

            @Override
            public void visitVariable(Variable variable) {
                addImport(DualitySelfResolvable.variable(context, variable.getName()).findDeclaration());
                super.visitVariable(variable);
            }

            private void addImport(Duality resolved) {
                if (resolved != null) {
                    FileContext dependency = resolved.getKingdom().getFileContext();
                    if (dependency != context) {
                        imports.add(new ImportFlow(dependency));
                    }
                }
            }
        });
        return imports;
    }

    public void visitExpresssions(ExpressionVisitor visitor) {
        parameters.visit(visitor);
        instructions.visit(visitor);
    }

    public InlineFunction getInlineFunction() {
        MixedDependency dependency = instructions.getReturn();
        ReturnFlow flow = dependency.getReturnFlow();
        if (flow != null) {
            Expression expression = flow.getExpression();
            if (flow.isSimple()) {
                return inline(expression);
            } else if (expression instanceof Function) {
                Function function = (Function) expression;
                if (function.getArgs().length == 0) {
                    return inline(function);
                } else if (function.getArgs().length == 1) {
                    Expression arg = function.getArgs()[0];
                    if (arg instanceof Variable) {
                        Variable variable = (Variable) arg;
                        if (parameters.getFlow(variable.getName()) != null) {
                            return inline(function);
                        }
                    }
                }
            }
        }
        return null;
    }

    @NotNull
    private InlineFunction inline(Expression expression) {
        return new InlineFunction(name, getArgNames(), expression);
    }

    private String[] getArgNames() {
        ArrayList<String> names = new ArrayList<>();
        for (Flow parameter : parameters.getList()) {
            names.add(((ParameterFlow)parameter).getName());
        }
        return names.toArray(new String[names.size()]);
    }

    @Override
    public KerboScriptNamedElement getSyntax() {
        return null; // TODO implement
    }

    @Override
    public FunctionFlow getSemantics() {
        return this;
    }

    public List<ParameterFlow> getParameters() {
        return parameters.getList();
    }

    public void checkUsages() {
        // TODO more elegant?
        visitExpresssions(new ExpressionVisitor() {
            @Override
            public void visitFunction(Function function) {
                FunctionFlow functionFlow = FlowSelfResolvable.function(context, function.getName()).findDeclaration();
                if (functionFlow!=null) {
                    if (functionFlow.getKingdom() instanceof DiffContext) {
                        functionFlow.addDependee(FunctionFlow.this);
                    }
                }
                addImport(functionFlow);
                super.visitFunction(function);
            }

            @Override
            public void visitVariable(Variable variable) {
                addImport(DualitySelfResolvable.variable(context, variable.getName()).findDeclaration());
                super.visitVariable(variable);
            }

            private void addImport(Reference declaration) {
                if (declaration!=null) {
                    LocalContext context = declaration.getKingdom();
                    Duality duality = FunctionFlow.this.context.getDeclarations(ReferableType.FILE).get(context.getFileContext().getName());
                    if (duality != null) {
                        ImportFlow importFlow = (ImportFlow) duality.getSemantics();
                        importFlow.addDependee(FunctionFlow.this);
                    }
                }
            }
        });
    }

    private static class FunctionParser extends InstructionsParser {
        private final InstructionsParser parser = new InstructionsParser(getContext());
        private boolean parametersParsed = false;

        @Override
        public boolean parseInstruction(KerboScriptInstruction instruction) throws SyntaxException {
            if (parametersParsed) {
                return parser.parseInstruction(instruction);
            } else if (instruction instanceof KerboScriptDeclareStmt) {
                KerboScriptDeclareStmt declareStmt = (KerboScriptDeclareStmt) instruction;
                KerboScriptDeclareParameterClause declareParameterClause = declareStmt.getDeclareParameterClause();
                if (declareParameterClause != null) {
                    addFlow(ParameterFlow.parse(declareParameterClause));
                } else if (declareStmt.getDeclareIdentifierClause() != null) {
                    parametersParsed = true;
                    return parser.parseInstruction(instruction);
                }
            } else {
                parametersParsed = true;
                return parser.parseInstruction(instruction);
            }
            return true;
        }

        public ContextBuilder getFlowContext() {
            return parser.getContext();
        }
    }
}
