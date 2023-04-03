package org.ifds;

import heros.DefaultSeeds;
import heros.FlowFunction;
import heros.FlowFunctions;
import heros.InterproceduralCFG;
import heros.flowfunc.Identity;
import heros.flowfunc.KillAll;
import sootup.analysis.interprocedural.ifds.DefaultJimpleIFDSTabulationProblem;
import sootup.core.jimple.basic.Immediate;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.JStaticFieldRef;
import sootup.core.jimple.common.stmt.*;
import sootup.core.model.SootMethod;

import java.util.*;

public class IFDSAnalysisProblem extends
        DefaultJimpleIFDSTabulationProblem<Map<Local, String>, InterproceduralCFG<Stmt, SootMethod>> {

    private SootMethod entryMethod;
    private SootMethod clinit;
    protected InterproceduralCFG<Stmt, SootMethod> icfg;
    private Map<SootMethod, Map<Local, String>> methodToConstants;

    public IFDSAnalysisProblem(InterproceduralCFG<Stmt, SootMethod> icfg, SootMethod entryMethod,
                               SootMethod clinit) {
        super(icfg);
        this.icfg = icfg;
        this.entryMethod = entryMethod;
        this.methodToConstants = new HashMap<>();
        this.clinit = clinit;
    }

    public Map<SootMethod, Map<Local, String>> getMethodToConstants() {
        return this.getMethodToConstants();
    }

    @Override
    protected FlowFunctions createFlowFunctionsFactory() {
        return new FlowFunctions<Stmt, Map<Local, String>, SootMethod>() {
            @Override
            public FlowFunction<Map<Local, String>> getNormalFlowFunction(Stmt curr, Stmt succ) {
                return getNormalFlow(curr, succ);
            }

            @Override
            public FlowFunction<Map<Local, String>> getCallFlowFunction(Stmt callStmt, SootMethod destinationMethod) {
                return getCallFlow(callStmt, destinationMethod);
            }

            @Override
            public FlowFunction<Map<Local, String>> getReturnFlowFunction(Stmt callSite, SootMethod calleeMethod, Stmt exitStmt, Stmt returnSite) {
                return getReturnFlow(callSite, calleeMethod, exitStmt, returnSite);
            }

            @Override
            public FlowFunction<Map<Local, String>> getCallToReturnFlowFunction(Stmt callSite, Stmt returnSite) {
                return getCallToReturnFlow(callSite, returnSite);
            }
        };
    }

    @Override
    protected Map<Local, String> createZeroValue() {
	/* This works for now because we're only tracking Strings. 
	 * Similar to other comments, I think we may want to encapsulate the "value"
	 * as an Object rather than using "".  If we were to expand this to also track
	 * ints, the "" string wouldn't be a good zero.
	 *
	 * It might be worth starting out with an type heirarchy like:
	 * ValueObject
	 *   - TopValue
	 *   - BottomValue
	 *   - StringValue
	 * for now and see if that can be made to work.  That allows adding new
	 * types of values (IntValue, etc) later.
	 */
        Map<Local, String> entryMap = new HashMap<>();
        for (Local l : entryMethod.getBody().getLocals()) {
            entryMap.put(l, "");
        }

        return entryMap;
    }

    @Override
    public Map<Stmt, Set<Map<Local, String>>> initialSeeds() {
        return DefaultSeeds.make(Collections.singleton
                (entryMethod.getBody().getStmtGraph().getStartingStmt()), zeroValue());
    }


    FlowFunction<Map<Local, String>> getNormalFlow(Stmt curr, Stmt succ) {
        final SootMethod m = interproceduralCFG().getMethodOf(curr);

        if (curr instanceof AbstractDefinitionStmt<?,?> definitionStmt) {
            final Value leftOp = definitionStmt.getLeftOp();
            if (leftOp instanceof Local leftOpLocal) {
                return new FlowFunction<Map<Local, String>>() {
                    @Override
                    public Set<Map<Local, String>> computeTargets(final Map<Local, String> source) {

                        Set<Map<Local,String>> res = new LinkedHashSet<>();
                        StringFoldingVisitor visitor = new StringFoldingVisitor(source);
			// What if it's a getstatic SomeOtherClass.foo?  Wouldn't we need to walk the definingClass of
			// the field's <clinit> to find the right value?
			// And also, only for static final fields.
                        if (definitionStmt.getRightOp() instanceof JStaticFieldRef fieldRef) {
                            visitor = new StringFoldingVisitor(source, clinit);
                        }
                        curr.accept(visitor);
                        res.add(visitor.getSetOut());
                        methodToConstants.put(m, visitor.getSetOut());
                        return res;

                    }
                };
            }
        }

        return Identity.v();
    }

    FlowFunction<Map<Local, String>> getCallFlow(Stmt callStmt, final SootMethod destinationMethod) {
        final SootMethod m = interproceduralCFG().getMethodOf(callStmt);

        AbstractInvokeExpr invokeExpr = callStmt.getInvokeExpr();
        final List<Immediate> args = invokeExpr.getArgs();

        return new FlowFunction<Map<Local, String>>() {

            @Override
            public Set<Map<Local, String>> computeTargets(Map<Local, String> source) {

                /* Do not map parameters for <clinit */
//                if (destinationMethod.getName().equals("<clinit>")) {
//                    return Collections.emptySet();
//                }

                LinkedHashSet<Map<Local,String>> res = new LinkedHashSet<>();
                Map<Local, String> constants = new HashMap<>();

                for (int i = 0; i < destinationMethod.getParameterCount(); i++) {
                    if (args.get(i) instanceof Local argLocal) {
                        constants.put(destinationMethod.getBody().getParameterLocal(i),
                                source.get(argLocal));
                    }
                }

                /* Fill in the other locals we don't know */
                for (Local l : destinationMethod.getBody().getLocals()) {
                    if (! constants.containsKey(l)) {
                        constants.put(l, "");
                    }
                }

                methodToConstants.put(destinationMethod, constants);
                res.add(constants);
                return res;

            }
        };
    }


    FlowFunction<Map<Local, String>> getReturnFlow(final Stmt callSite, final SootMethod calleeMethod,
                                                   Stmt exitStmt, Stmt returnSite) {

        SootMethod caller = interproceduralCFG().getMethodOf(returnSite);
        if (callSite instanceof AbstractDefinitionStmt<?, ?> definitionStmt) {
            if (definitionStmt.getLeftOp() instanceof Local) {
                final Local leftOpLocal = (Local) definitionStmt.getLeftOp();
                if (exitStmt instanceof JReturnStmt returnStmt) {
                    return new FlowFunction<Map<Local, String>>() {
                        @Override
                        public Set<Map<Local, String>> computeTargets(Map<Local, String> source) {
                            Set<Map<Local, String>> ret = new LinkedHashSet<>();
                            String returnStr = "";
                            if (returnStmt.getOp() instanceof Local op) {
                                /* for debugging */
                                returnStr = source.get(op);

                            } else if (returnStmt.getOp() instanceof StringConstant constant) {
                                returnStr = constant.getValue();
                            }

                            methodToConstants.get(caller).put(leftOpLocal, returnStr);
                            ret.add(methodToConstants.get(caller));
                            return ret;
                        }
                    };
                }
            }
        }
	// What does this do?  Good place for a descriptive comment =)
        return KillAll.v();
    }

    FlowFunction<Map<Local, String>> getCallToReturnFlow(final Stmt callSite, Stmt returnSite) {

        final SootMethod m = interproceduralCFG().getMethodOf(callSite);

        return new FlowFunction<Map<Local, String>>() {
            @Override
            public Set<Map<Local, String>> computeTargets(Map<Local, String> source) {

		// Is this correct?  I thought CallToReturnFlow was the set of values
		// that was propagated over a call without peeking into the call. Should
		// this not propagate the set of locals from before the call to after?
		// ie:
		// local a = ...
		// local b = ...
		// invokevirt M.c()
		// <-- propagate a & b here since we know they are unchanged?
		//
		// Or am I misunderstanding?

                Set<Map<Local,String>> res = new LinkedHashSet<>();
                StringFoldingVisitor visitor = new StringFoldingVisitor(source);
                callSite.accept(visitor);
                res.add(visitor.getSetOut());
                methodToConstants.put(m, visitor.getSetOut());
                return res;

            }
        };
    }
}
