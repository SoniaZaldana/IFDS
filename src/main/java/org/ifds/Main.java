package org.ifds;

import heros.InterproceduralCFG;
import sootup.analysis.interprocedural.ifds.JimpleIFDSSolver;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootMethod;

import java.util.Set;

public class Main {
    public static void main(String[] args) {

        IFDSSetUp setUp = new IFDSSetUp();
        JimpleIFDSSolver<?, InterproceduralCFG<Stmt, SootMethod>> analysis =
                setUp.executeStaticAnalysis("jlink.Test");

        Set<?> result = setUp.getResultsAtLastStatement(analysis);

        System.out.println("done");
    }
}