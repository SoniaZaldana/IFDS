package org.ifds;

import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.ref.IdentityRef;
import sootup.core.jimple.common.ref.JFieldRef;
import sootup.core.jimple.common.ref.JInstanceFieldRef;
import sootup.core.jimple.common.stmt.*;
import sootup.core.jimple.javabytecode.stmt.*;
import sootup.core.jimple.visitor.StmtVisitor;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;

import java.util.HashMap;
import java.util.Map;

public class StringFoldingVisitor implements StmtVisitor {

    private static final String TOP = "*";		// What if the local actually holds a String with the value "*"?
    private static final String SB_APPEND = "<java.lang.StringBuilder: java.lang.StringBuilder append(java.lang.String)>";
    private static final String SB_TO_STRING = "<java.lang.StringBuilder: java.lang.String toString()>";
    private static final String SB_INIT_PLAIN = "<java.lang.StringBuilder: void <init>()>";
    private static final String SB_INIT_STR = "<java.lang.StringBuilder: void <init>(java.lang.String)>";
    private static final String STRING_CONCAT = "<java.lang.String: java.lang.String concat(java.lang.String)>";

    // is String the best thing to use for the mapping here?  Would it be better to 
    // use an internal class to propagate the String contents around?
    private Map<Local, String> setIn;
    private Map<Local, String> setOut;

    private SootMethod clinit;

    public StringFoldingVisitor(Map<Local, String> setIn) {
        this.setIn = setIn;
        this.setOut = new HashMap<>();
    }

    public StringFoldingVisitor(Map<Local, String> setIn, SootMethod clinit) {
        this(setIn);
        this.clinit = clinit;
    }

    public Map<Local, String> getSetOut() {
        return setOut;
    }

    @Override
    public void caseBreakpointStmt(JBreakpointStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void caseInvokeStmt(JInvokeStmt stmt) {
        defaultCaseStmt(stmt);

        if (AbstractInstanceInvokeExpr.class.isAssignableFrom(stmt.getInvokeExpr().getClass())) {
            AbstractInstanceInvokeExpr iExpr = (AbstractInstanceInvokeExpr) stmt.getInvokeExpr();
            // currently, only support for: append, toString and init for StringBuilder.
	    // And concat given STRING_CONCAT?
            stringBuilderOrStringJavaLang(iExpr, iExpr.getBase());

        }
    }

    private boolean stringBuilderOrStringJavaLang(AbstractInvokeExpr iExpr, Local toReassign) {

        MethodSignature iMethod = iExpr.getMethodSignature();
        Local lBase = null;
        if (iExpr instanceof AbstractInstanceInvokeExpr exp) {
            lBase = exp.getBase();
        }
	// Why does one check the full toString and the other just the name?  Supporting multiple forms of CONCAT?
	// Depending on the format of toString is usually pretty brittle.  Better to depend on methods that have a more
	// defined contract (like getName(), getSignature(), etc)
        if (iMethod.toString().equals(SB_APPEND) || iMethod.getName().equals(STRING_CONCAT)) {
            Value arg = iExpr.getArg(0);
            String toAppendStr = stringBuilderParamValue(arg);
            if (lBase != null) {
                setOut.put(toReassign, concat(setIn.get(lBase), toAppendStr));
            } else {
                setOut.put(toReassign, concat(setIn.get(toReassign), toAppendStr));
            }
            return true;
        } else if (iMethod.toString().equals(SB_TO_STRING)) {
            setOut.put(toReassign, setIn.get(lBase));
            return true;
        } else if (iMethod.toString().equals(SB_INIT_PLAIN)) {
            setOut.put(toReassign, "");
            return true;
        } else if (iMethod.toString().equals(SB_INIT_STR)) {
            setOut.put(toReassign, stringBuilderParamValue(iExpr.getArg(0)));
        }
        return false;

    }

    private String stringBuilderParamValue(Value arg) {
        String toAppendStr = TOP;

        if (StringConstant.class.isAssignableFrom(arg.getClass())) {
            toAppendStr = ((StringConstant) arg).getValue();
        } else if (Local.class.isAssignableFrom(arg.getClass())) {
            toAppendStr = setIn.get(arg);
        }

        return toAppendStr;

    }
    private String concat(String s1, String s2) {
        if (s1.equals(TOP) || s2.equals(TOP)) {
            return TOP;
        } else {
            return s1 + s2;
        }
    }


    /**
     * Similar question applies to many places about the use of ::isAssignableFrom.
     * The reason I ask about it is that it's harder to follow the code when it isn't
     * written as idiomatic java unless there's a reason to use different patterns.
     * If there is, it helps to have a comment somewhere to say why.
     *
     */
    @Override
    public void caseAssignStmt(JAssignStmt<?, ?> stmt) {
        defaultCaseStmt(stmt);
        Value left = stmt.getLeftOp();
        Value right = stmt.getRightOp();
        if (Local.class.isAssignableFrom(left.getClass())) {
            Local lLocal = (Local) left;
            if (AbstractInvokeExpr.class.isAssignableFrom(right.getClass())) {
                AbstractInvokeExpr iExpr = (AbstractInvokeExpr) right;
                stringBuilderOrStringJavaLang(iExpr, lLocal);
            } else if (Local.class.isAssignableFrom(right.getClass())) {
                Local rLocal = (Local) right;
                // we simply update the value of left with whatever there was in right.
                setOut.put(lLocal, setIn.get(rLocal));
            } else if (StringConstant.class.isAssignableFrom(right.getClass())) {
                // this time the new value of lLocal is the constant string
                setOut.put(lLocal, ((StringConstant) right).getValue());
		// What is the difference between this case and the one before?  In either case, 
		// we're assigning what was on the right to the local on the left?  I guess in this
		// one we're only assigning the value from the right?  Not 100% clear on why that
		// matters here though.
            } else if (JNewExpr.class.isAssignableFrom(right.getClass())) {
                JNewExpr newExpr = (JNewExpr) right;
                if (newExpr.getType().toString().equals("java.lang.StringBuilder")) {
                    setOut.putAll(setIn);
		    // Why does a "new StringBuilder" call pass through all the locals?
		    // The setOut == the setIn, correct?
                }
            } else if (JInstanceFieldRef.class.isAssignableFrom(right.getClass())) {

                setOut.put(lLocal, TOP);
            } else if (JFieldRef.class.isAssignableFrom(right.getClass())) {

                String str = "";
                if (StringConstant.class.isAssignableFrom(right.getClass())) {
                    // We have a constant so we are done
                    str = ((StringConstant) right).getValue();
                } else {

                    JFieldRef fRef = (JFieldRef) right;
                    str = lookUpFieldRef(fRef);
                }
                setOut.put(lLocal, str);

            } else {
                // As we don't support any other case we assign TOP

                setOut.put(lLocal, TOP);
            }
        } else {
            // If left is not a local, we don't care.
        }
    }

    /**
     * Should this be limited to static final fields?  We don't know at the time of the static
     * analysis if the value written to a non-final field has been changed outside of the
     * <clinit> method.
     *
     * How are static final fields set with ConstantValue_attribute handled? 
     * ie: static final FOO = "Foo"; doesn't need to be set in a <clinit>
     */
    private String lookUpFieldRef(JFieldRef fRef) {

        for (Stmt u : clinit.getBody().getStmts()) {
            if (JAssignStmt.class.isAssignableFrom(u.getClass())) {
                JAssignStmt aStmt = (JAssignStmt) u;
                Value left = aStmt.getLeftOp();
                Value right = aStmt.getRightOp();

		/* Why use Class::isAssignableFrom rather than instanceof? 
		 * ie: left instanceof JFieldRef?
		 * The pattern matching for instanceof makes it even nicer:
		 *    left instanceof JFieldRef aFieldRef
		 * and avoids the need to cast afterwards.
		 *
		 * If there's a reason to use the isAssignableFrom pattern I'm not seeing?
		 */
                if (JFieldRef.class.isAssignableFrom(left.getClass())) {
                    JFieldRef aFieldRef = (JFieldRef) left;
                    // If the static field is the one that we are looking for...
                    if (aFieldRef.getFieldSignature().equals(fRef.getFieldSignature())) {
                        // we then retrieve its constant value if it is a string!
                        if (StringConstant.class.isAssignableFrom(right.getClass())) {
                            StringConstant vStr = (StringConstant) right;
                            return vStr.getValue();
                        }

                        /* TODO, if we want to do interprocedural analysis on a field assignment i.e.
                            static {
                                staticStr = foo("Sonia").
                            }
                            We need to set <clinit> as the entry point and reformulate the IFDS problem
                            to propagate field values as opposed to locals.
                         */
                    }
                }
            }
        }

        return TOP;
    }

    @Override
    public void caseIdentityStmt(JIdentityStmt<?> stmt) {
        defaultCaseStmt(stmt);

        Value left = stmt.getLeftOp();
        Value right = stmt.getRightOp();

        if (Local.class.isAssignableFrom(left.getClass())) {
            Local lLocal = (Local) left;

            if (IdentityRef.class.isAssignableFrom(right.getClass())) {
                // the local is not a constant and depends on external input
		// I don't understand what the comment is trying to say here.
                setOut.put(lLocal, setIn.get(lLocal));
            }
        }
	// Don't we need to poison the Local here (set it to TOP) if it has had
	// soemthing other than a String assigned to it?
    }

    @Override
    public void caseEnterMonitorStmt(JEnterMonitorStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void caseExitMonitorStmt(JExitMonitorStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void caseGotoStmt(JGotoStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void caseIfStmt(JIfStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void caseNopStmt(JNopStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void caseRetStmt(JRetStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void caseReturnStmt(JReturnStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void caseReturnVoidStmt(JReturnVoidStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void caseSwitchStmt(JSwitchStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void caseThrowStmt(JThrowStmt stmt) {
        defaultCaseStmt(stmt);
    }

    @Override
    public void defaultCaseStmt(Stmt stmt) {
        setOut.putAll(setIn);
    }
}
