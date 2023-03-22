package org.ifds;

import heros.InterproceduralCFG;
import sootup.analysis.interprocedural.icfg.JimpleBasedInterproceduralCFG;
import sootup.analysis.interprocedural.ifds.JimpleIFDSSolver;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaIdentifierFactory;
import sootup.java.core.JavaProject;
import sootup.java.core.language.JavaLanguage;
import sootup.java.core.types.JavaClassType;
import sootup.java.core.views.JavaView;

import java.util.List;
import java.util.Set;

public class IFDSSetUp {

    protected JavaView view;
    protected MethodSignature entryMethodSignature;
    protected SootMethod entryMethod;
    protected SootMethod clinit;
    private static JimpleIFDSSolver<?, InterproceduralCFG<Stmt, SootMethod>> solved = null;

    public JimpleIFDSSolver<?, InterproceduralCFG<Stmt, SootMethod>> executeStaticAnalysis(String targetClassName) {
        setupSoot(targetClassName, "/home/szaldana/IdeaProjects/module-project/outDir/jlink.module");
        runAnalysis();
        if (solved == null) {
            throw new NullPointerException("Something went wrong solving the IFDS problem!");
        }
        return solved;
    }

    public void runAnalysis() {

        JimpleBasedInterproceduralCFG icfg = new JimpleBasedInterproceduralCFG(
                view,
                entryMethodSignature,
                false,
                false);

        IFDSAnalysisProblem problem = new IFDSAnalysisProblem(icfg, entryMethod, clinit);
        JimpleIFDSSolver<?, InterproceduralCFG<Stmt, SootMethod>> solver = new JimpleIFDSSolver<>(problem);
        solver.solve(entryMethod.getDeclaringClassType().getClassName());
        solved = solver;
    }

    public void setupSoot(String targetClassName, String inputPath) {
        JavaProject javaProject =
                JavaProject.builder(new JavaLanguage(9))
                        .addInputLocation(
                                new JavaClassPathAnalysisInputLocation(inputPath))
                        .build();

        view = javaProject.createOnDemandView();

        JavaIdentifierFactory identifierFactory = JavaIdentifierFactory.getInstance();
        JavaClassType mainClassSignature = identifierFactory.getClassType(targetClassName);

        SootClass<?> sc = view.getClass(mainClassSignature).get();
        entryMethod = sc.getMethods().stream().filter(m -> m.getName().equals("main")).findFirst().get();
        clinit = sc.getMethods().stream().filter(m -> m.getName().equals("<clinit>")).findFirst().get();

        entryMethodSignature = entryMethod.getSignature();
        assert(entryMethod != null);
    }

    public Set<?> getResultsAtLastStatement(
            JimpleIFDSSolver<?, InterproceduralCFG<Stmt, SootMethod>> analysis) {
        SootMethod m = entryMethod;
        List<Stmt> stmts = m.getBody().getStmts();
        Set<?> rawSet = analysis.ifdsResultsAt(stmts.get(stmts.size() - 1));
        System.out.println(rawSet);
        return rawSet;
    }

}