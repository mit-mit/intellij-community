/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.streamToLoop;

import com.intellij.codeInspection.BaseJavaBatchLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.java.lexer.JavaLexer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.PsiDiamondTypeUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.util.RefactoringUtil;
import com.siyeh.ig.psiutils.*;
import one.util.streamex.IntStreamEx;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

import static com.intellij.codeInspection.streamToLoop.Operation.FlatMapOperation;

/**
 * @author Tagir Valeev
 */
public class StreamToLoopInspection extends BaseJavaBatchLocalInspectionTool {
  private static final Logger LOG = Logger.getInstance(StreamToLoopInspection.class);

  // To quickly filter out most of the non-interesting method calls
  private static final Set<String> SUPPORTED_TERMINALS = StreamEx.of("count", "sum", "summaryStatistics", "reduce", "collect",
                                                                     "findFirst", "findAny", "anyMatch", "allMatch", "noneMatch",
                                                                     "toArray", "average", "forEach", "forEachOrdered", "min", "max").toSet();

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    if (!PsiUtil.isLanguageLevel8OrHigher(holder.getFile())) {
      return PsiElementVisitor.EMPTY_VISITOR;
    }
    return new JavaElementVisitor() {
      @Override
      public void visitMethodCallExpression(PsiMethodCallExpression call) {
        super.visitMethodCallExpression(call);
        PsiReferenceExpression expression = call.getMethodExpression();
        PsiElement nameElement = expression.getReferenceNameElement();
        if (nameElement == null || !SUPPORTED_TERMINALS.contains(nameElement.getText()) || !isSupportedCodeLocation(call)) return;
        PsiMethod method = call.resolveMethod();
        if(method == null) return;
        PsiClass aClass = method.getContainingClass();
        if(!InheritanceUtil.isInheritor(aClass, CommonClassNames.JAVA_UTIL_STREAM_BASE_STREAM)) return;
        PsiMethodCallExpression currentCall = call;
        while(true) {
          Operation op = createOperationFromCall(StreamVariable.STUB, currentCall);
          if(op == null) return;
          if(op instanceof SourceOperation) {
            TextRange range;
            if(isOnTheFly && InspectionProjectProfileManager.isInformationLevel(getShortName(), call)) {
              range = new TextRange(0, call.getTextLength());
            } else {
              range = nameElement.getTextRange().shiftRight(-call.getTextOffset());
            }
            holder.registerProblem(call, range, "Replace stream API chain with loop", new ReplaceStreamWithLoopFix());
            return;
          }
          PsiExpression qualifier = currentCall.getMethodExpression().getQualifierExpression();
          if(!(qualifier instanceof PsiMethodCallExpression)) return;
          currentCall = (PsiMethodCallExpression)qualifier;
        }
      }
    };
  }

  private static boolean isSupportedCodeLocation(PsiMethodCallExpression call) {
    PsiElement cur = call;
    PsiElement parent = cur.getParent();
    while(parent instanceof PsiExpression || parent instanceof PsiExpressionList) {
      if(parent instanceof PsiLambdaExpression) {
        return true;
      }
      if(parent instanceof PsiPolyadicExpression) {
        PsiPolyadicExpression polyadicExpression = (PsiPolyadicExpression)parent;
        IElementType type = polyadicExpression.getOperationTokenType();
        if ((type.equals(JavaTokenType.ANDAND) || type.equals(JavaTokenType.OROR)) && polyadicExpression.getOperands()[0] != cur) {
          // not the first in the &&/|| chain: we cannot properly generate code which would short-circuit as well
          return false;
        }
      }
      if(parent instanceof PsiConditionalExpression && ((PsiConditionalExpression)parent).getCondition() != cur) {
        return false;
      }
      if(parent instanceof PsiMethodCallExpression) {
        PsiReferenceExpression methodExpression = ((PsiMethodCallExpression)parent).getMethodExpression();
        if(methodExpression.textMatches("this") || methodExpression.textMatches("super")) {
          return false;
        }
      }
      cur = parent;
      parent = cur.getParent();
    }
    if(parent instanceof PsiReturnStatement || parent instanceof PsiExpressionStatement) return true;
    if(parent instanceof PsiLocalVariable) {
      PsiElement grandParent = parent.getParent();
      if(grandParent instanceof PsiDeclarationStatement && ((PsiDeclarationStatement)grandParent).getDeclaredElements().length == 1) {
        return true;
      }
    }
    if(parent instanceof PsiForeachStatement && ((PsiForeachStatement)parent).getIteratedValue() == cur) return true;
    if(parent instanceof PsiIfStatement && ((PsiIfStatement)parent).getCondition() == cur) return true;
    return false;
  }

  @Nullable
  static Operation createOperationFromCall(StreamVariable outVar, PsiMethodCallExpression call) {
    PsiMethod method = call.resolveMethod();
    if(method == null) return null;
    PsiClass aClass = method.getContainingClass();
    if(aClass == null) return null;
    PsiExpression[] args = call.getArgumentList().getExpressions();
    String name = method.getName();
    String className = aClass.getQualifiedName();
    if(className == null) return null;
    PsiType callType = call.getType();
    if(callType == null) return null;
    if(InheritanceUtil.isInheritor(aClass, CommonClassNames.JAVA_UTIL_STREAM_BASE_STREAM) &&
       !method.getModifierList().hasExplicitModifier(PsiModifier.STATIC)) {
      PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
      if(qualifier != null) {
        PsiType elementType = StreamApiUtil.getStreamElementType(qualifier.getType());
        if(elementType == null || ((elementType instanceof PsiClassType) && ((PsiClassType)elementType).isRaw())) {
          // Raw type in any stream step is not supported
          return null;
        }
        Operation op = Operation.createIntermediate(name, args, outVar, elementType);
        if (op != null) return op;
        PsiElement parent = call.getParent();
        boolean isVoid = parent instanceof PsiExpressionStatement ||
                         (parent instanceof PsiLambdaExpression &&
                          PsiType.VOID.equals(LambdaUtil.getFunctionalInterfaceReturnType((PsiLambdaExpression)parent)));
        op = TerminalOperation.createTerminal(name, args, elementType, callType, isVoid);
        if (op != null) return op;
      }
    }
    return SourceOperation.createSource(call);
  }


  @Nullable
  static List<OperationRecord> extractOperations(StreamVariable outVar, PsiMethodCallExpression terminalCall) {
    List<OperationRecord> operations = new ArrayList<>();
    PsiMethodCallExpression currentCall = terminalCall;
    StreamVariable lastVar = outVar;
    Operation next = null;
    while(true) {
      Operation op = createOperationFromCall(lastVar, currentCall);
      if(op == null) return null;
      if(next != null) {
        Operation combined = op.combineWithNext(next);
        if (combined != null) {
          op = combined;
          operations.remove(operations.size() - 1);
        }
      }
      OperationRecord or = new OperationRecord();
      or.myOperation = op;
      or.myOutVar = lastVar;
      operations.add(or);
      if(op instanceof SourceOperation) {
        or.myInVar = StreamVariable.STUB;
        Collections.reverse(operations);
        return operations;
      }
      PsiExpression qualifier = currentCall.getMethodExpression().getQualifierExpression();
      if(!(qualifier instanceof PsiMethodCallExpression)) return null;
      currentCall = (PsiMethodCallExpression)qualifier;
      if(op.changesVariable()) {
        PsiType type = StreamApiUtil.getStreamElementType(currentCall.getType());
        if(type == null) return null;
        lastVar = new StreamVariable(type.getCanonicalText());
      }
      or.myInVar = lastVar;
      next = op;
    }
  }

  @Contract("null -> null")
  @Nullable
  static TerminalOperation getTerminal(List<OperationRecord> operations) {
    if (operations == null || operations.isEmpty()) return null;
    OperationRecord record = operations.get(operations.size()-1);
    if(record.myOperation instanceof TerminalOperation) {
      return (TerminalOperation)record.myOperation;
    }
    return null;
  }

  static class ReplaceStreamWithLoopFix implements LocalQuickFix {
    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace Stream API chain with loop";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      PsiElement element = descriptor.getStartElement();
      if(!(element instanceof PsiMethodCallExpression)) return;
      PsiMethodCallExpression terminalCall = (PsiMethodCallExpression)element;
      if(!isSupportedCodeLocation(terminalCall)) return;
      PsiType resultType = terminalCall.getType();
      if (resultType == null) return;
      List<OperationRecord> operations = extractOperations(StreamVariable.STUB, terminalCall);
      TerminalOperation terminal = getTerminal(operations);
      if (terminal == null) return;
      allOperations(operations).forEach(or -> or.myOperation.suggestNames(or.myInVar, or.myOutVar));
      PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
      terminalCall = ensureCodeBlock(terminalCall, factory);
      if(terminalCall == null) return;
      PsiStatement statement = PsiTreeUtil.getParentOfType(terminalCall, PsiStatement.class);
      LOG.assertTrue(statement != null);
      PsiExpression temporaryStreamPlaceholder =
        (PsiExpression)terminalCall
          .replace(factory.createExpressionFromText("((" + resultType.getCanonicalText() + ")$streamReplacement$)", terminalCall));
      try {
        StreamToLoopReplacementContext context =
          new StreamToLoopReplacementContext(statement, operations, temporaryStreamPlaceholder);
        registerVariables(operations, context);
        String replacement = "";
        for (OperationRecord or : StreamEx.ofReversed(operations)) {
          replacement = or.myOperation.wrap(or.myInVar, or.myOutVar, replacement, context);
        }
        for (String declaration : context.getDeclarations()) {
          addStatement(project, statement, factory.createStatementFromText(declaration, statement));
        }
        for (PsiStatement addedStatement : ((PsiBlockStatement)factory.createStatementFromText("{" + replacement + "}", statement))
          .getCodeBlock().getStatements()) {
          addStatement(project, statement, addedStatement);
        }

        PsiElement result = context.makeFinalReplacement();
        if(result != null) {
          normalize(project, result);
        }
      }
      catch (Exception ex) {
        String text = terminalCall.getText();
        if(temporaryStreamPlaceholder.isPhysical()) {
          // Just in case if something went wrong: at least try to restore the original stream code
          temporaryStreamPlaceholder.replace(factory.createExpressionFromText(text, temporaryStreamPlaceholder));
        }
        LOG.error("Error converting Stream to loop: "+text, ex);
      }
    }

    private static void addStatement(@NotNull Project project, PsiStatement statement, PsiStatement context) {
      PsiElement element = statement.getParent().addBefore(context, statement);
      normalize(project, element);
    }

    private static void normalize(@NotNull Project project, PsiElement element) {
      element = JavaCodeStyleManager.getInstance(project).shortenClassReferences(element);
      PsiDiamondTypeUtil.removeRedundantTypeArguments(element);
      CodeStyleManager.getInstance(project).reformat(element);
    }

    /**
     * Ensures that given call is surrounded by {@link PsiCodeBlock} (that is, it has a parent statement
     * which is located inside the code block). If not, tries to create a code block.
     *
     * <p>
     * Note that the expression is not necessarily a child of {@link PsiExpressionStatement}; it could be a subexpression,
     * {@link PsiIfStatement}, etc.
     * </p>
     *
     * @param expression an expression which should be located inside the code block
     * @param factory a factory to use to generate code if necessary
     * @return a passed expression if it's already surrounded by code block and no changes are necessary;
     *         a replacement expression (which is equivalent to the passed expression) if a new code block was created;
     *         {@code null} if the expression cannot be surrounded with code block.
     */
    @Nullable
    private static PsiMethodCallExpression ensureCodeBlock(PsiMethodCallExpression expression, PsiElementFactory factory) {
      PsiElement parent = RefactoringUtil.getParentStatement(expression, false);
      if (parent == null) return null;
      if (parent instanceof PsiStatement && parent.getParent() instanceof PsiCodeBlock) return expression;
      Object marker = new Object();
      PsiTreeUtil.mark(expression, marker);
      PsiElement copy = parent.copy();
      PsiElement newParent;
      if (parent instanceof PsiExpression) {
        PsiLambdaExpression lambda = (PsiLambdaExpression)parent.getParent();
        String replacement = PsiType.VOID.equals(LambdaUtil.getFunctionalInterfaceReturnType(lambda)) ? "{a;}" : "{return a;}";
        PsiElement block = parent.replace(factory.createCodeBlockFromText(replacement, lambda));
        newParent = LambdaUtil.extractSingleExpressionFromBody(block).replace(copy);
      } else {
        PsiBlockStatement blockStatement = (PsiBlockStatement)parent.replace(factory.createStatementFromText("{}", parent));
        newParent = blockStatement.getCodeBlock().add(copy);
      }
      return (PsiMethodCallExpression)PsiTreeUtil.releaseMark(newParent, marker);
    }

    private static StreamEx<OperationRecord> allOperations(List<OperationRecord> operations) {
      return StreamEx.of(operations)
        .flatMap(or -> or.myOperation.nestedOperations().append(or));
    }

    private static void registerVariables(List<OperationRecord> operations, StreamToLoopReplacementContext context) {
      allOperations(operations).map(or -> or.myOperation).forEach(op -> op.registerUsedNames(context::addUsedVar));
      allOperations(operations).map(or -> or.myInVar).distinct().forEach(var -> var.register(context));
    }
  }

  static class StreamToLoopReplacementContext {
    private final boolean myHasNestedLoops;
    private final String mySuffix;
    private final PsiStatement myStatement;
    private final Set<String> myUsedNames;
    private final Set<String> myUsedLabels;
    private final List<String> myDeclarations = new ArrayList<>();
    private PsiElement myPlaceholder;
    private final PsiElementFactory myFactory;
    private String myLabel;
    private String myFinisher;

    StreamToLoopReplacementContext(PsiStatement statement, List<OperationRecord> records, @NotNull PsiExpression placeholder) {
      myStatement = statement;
      myFactory = JavaPsiFacade.getElementFactory(myStatement.getProject());
      myHasNestedLoops = records.stream().anyMatch(or -> or.myOperation instanceof FlatMapOperation);
      myPlaceholder = placeholder;
      mySuffix = myHasNestedLoops ? "Outer" : "";
      myUsedNames = new HashSet<>();
      myUsedLabels = StreamEx.iterate(statement, Objects::nonNull, PsiElement::getParent).select(PsiLabeledStatement.class)
        .map(PsiLabeledStatement::getName).toSet();
    }

    StreamToLoopReplacementContext(StreamToLoopReplacementContext parentContext, List<OperationRecord> records) {
      myUsedNames = parentContext.myUsedNames;
      myUsedLabels = parentContext.myUsedLabels;
      myPlaceholder = null;
      myStatement = parentContext.myStatement;
      myFactory = parentContext.myFactory;
      myHasNestedLoops = records.stream().anyMatch(or -> or.myOperation instanceof FlatMapOperation);
      mySuffix = "Inner";
    }

    public void addUsedVar(String name) {
      myUsedNames.add(name);
    }

    @Nullable
    private String allocateLabel() {
      if(!myHasNestedLoops) return null;
      if(myLabel == null) {
        String base = mySuffix.toUpperCase(Locale.ENGLISH);
        myLabel = IntStreamEx.ints().mapToObj(i -> i == 0 ? base : base + i)
          .remove(myUsedLabels::contains).findFirst().orElseThrow(IllegalArgumentException::new);
        myUsedLabels.add(myLabel);
      }
      return myLabel;
    }

    public String getLoopLabel() {
      return myLabel == null ? "" : myLabel + ":\n";
    }

    public String getBreakStatement() {
      String label = allocateLabel();
      return label == null ? "break;\n" : "break "+label+";\n";
    }

    public List<String> getDeclarations() {
      return myDeclarations;
    }

    public String registerVarName(Collection<String> variants) {
      if(variants.isEmpty()) {
        return registerVarName(Collections.singleton("val"));
      }
      for(int idx = 0; ; idx++) {
        for(String variant : variants) {
          String name = idx == 0 ? variant : variant + idx;
          if(!isUsed(name)) {
            myUsedNames.add(name);
            return name;
          }
        }
      }
    }

    private boolean isUsed(String varName) {
      return myUsedNames.contains(varName) || JavaLexer.isKeyword(varName, LanguageLevel.HIGHEST) ||
             !varName.equals(JavaCodeStyleManager.getInstance(myStatement.getProject()).suggestUniqueVariableName(varName, myStatement, true));
    }

    public String declare(String desiredName, String type, String initializer) {
      String name = registerVarName(
        mySuffix.isEmpty() ? Collections.singleton(desiredName) : Arrays.asList(desiredName, desiredName + mySuffix));
      myDeclarations.add(type + " " + name + " = " + initializer + ";");
      return name;
    }

    public void addInitStep(String initStatement) {
      myDeclarations.add(initStatement);
    }

    public String declareResult(String desiredName, String type, String initializer, boolean finalResult) {
      if(finalResult && myPlaceholder.getParent() instanceof PsiVariable) {
        PsiVariable var = (PsiVariable)myPlaceholder.getParent();
        if(var.getType().equalsToText(type) && var.getParent() instanceof PsiDeclarationStatement) {
          PsiDeclarationStatement declaration = (PsiDeclarationStatement)var.getParent();
          if(declaration.getDeclaredElements().length == 1) {
            myPlaceholder = declaration;
            PsiVariable copy = (PsiVariable)var.copy();
            PsiExpression oldInitializer = copy.getInitializer();
            LOG.assertTrue(oldInitializer != null);
            oldInitializer.replace(createExpression(initializer));
            myDeclarations.add(copy.getText());
            return var.getName();
          }
        }
      }
      String name = registerVarName(Arrays.asList(desiredName, "result"));
      myDeclarations.add(type + " " + name + " = " + initializer + ";");
      if(myFinisher != null) {
        throw new IllegalStateException("Finisher is already defined");
      }
      setFinisher(name);
      return name;
    }

    public PsiElement makeFinalReplacement() {
      LOG.assertTrue(myPlaceholder != null);
      if (myFinisher == null || myPlaceholder instanceof PsiStatement) {
        myPlaceholder.delete();
        return null;
      }
      else {
        PsiExpression expression = myFactory.createExpressionFromText(myFinisher, myPlaceholder);
        PsiElement parent = myPlaceholder.getParent();
        if (parent instanceof PsiExpression && ParenthesesUtils.areParenthesesNeeded(expression, (PsiExpression)parent, false)) {
          expression = myFactory.createExpressionFromText("("+myFinisher+")", myPlaceholder);
        }
        return myPlaceholder.replace(expression);
      }
    }

    public void setFinisher(String finisher) {
      myFinisher = finisher;
    }

    public void setFinisher(ConditionalExpression conditionalExpression) {
      if(conditionalExpression instanceof ConditionalExpression.Optional) {
        conditionalExpression = tryUnwrapOptional((ConditionalExpression.Optional)conditionalExpression, expr -> true);
      }
      setFinisher(conditionalExpression.asExpression());
    }

    public String assignAndBreak(ConditionalExpression conditionalExpression) {
      Predicate<PsiElement> predicate = expr -> PsiUtil.skipParenthesizedExprUp(expr.getParent()) instanceof PsiReturnStatement;
      if(conditionalExpression instanceof ConditionalExpression.Optional) {
        conditionalExpression = tryUnwrapOptional((ConditionalExpression.Optional)conditionalExpression, predicate);
      }
      if(conditionalExpression instanceof ConditionalExpression.Boolean) {
        conditionalExpression = tryUnwrapBoolean((ConditionalExpression.Boolean)conditionalExpression);
      }
      if(predicate.test(myPlaceholder)) {
        setFinisher(conditionalExpression.getFalseBranch());
        return "return " + conditionalExpression.getTrueBranch() + ";";
      }
      PsiElement parent = PsiUtil.skipParenthesizedExprUp(myPlaceholder.getParent());
      if(parent instanceof PsiIfStatement && conditionalExpression.getTrueBranch().equals(String.valueOf(true))) {
        PsiIfStatement ifStatement = (PsiIfStatement)parent;
        if(ifStatement.getElseBranch() == null) {
          PsiStatement thenStatement = ControlFlowUtils.stripBraces(ifStatement.getThenBranch());
          if(thenStatement instanceof PsiReturnStatement || thenStatement instanceof PsiThrowStatement) {
            myPlaceholder = parent;
            return thenStatement.getText();
          }
          if(thenStatement instanceof PsiExpressionStatement) {
            myPlaceholder = parent;
            return thenStatement.getText() + "\n" + getBreakStatement();
          }
        }
      }
      if(conditionalExpression instanceof ConditionalExpression.Optional && myPlaceholder instanceof PsiExpression) {
        PsiMethodCallExpression call = ExpressionUtils.getCallForQualifier((PsiExpression)myPlaceholder);
        if(call != null && call.getParent() instanceof PsiExpressionStatement) {
          PsiExpression[] args = call.getArgumentList().getExpressions();
          if(args.length == 1 && "ifPresent".equals(call.getMethodExpression().getReferenceName())) {
            FunctionHelper fn = FunctionHelper.create(args[0], 1);
            if(fn != null) {
              fn.transform(this, ((ConditionalExpression.Optional)conditionalExpression).unwrap("").getTrueBranch());
              myPlaceholder = call.getParent();
              return fn.getText() + ";\n" + getBreakStatement();
            }
          }
        }
      }
      String found =
        declareResult(conditionalExpression.getCondition(), conditionalExpression.getType(), conditionalExpression.getFalseBranch(), false);
      return found + " = " + conditionalExpression.getTrueBranch() + ";\n" + getBreakStatement();
    }

    private ConditionalExpression tryUnwrapBoolean(ConditionalExpression.Boolean condition) {
      if (myPlaceholder instanceof PsiExpression) {
        PsiExpression negation = BoolUtils.findNegation((PsiExpression)myPlaceholder);
        if (negation != null) {
          myPlaceholder = negation;
          condition = condition.negate();
        }
        PsiElement parent = PsiUtil.skipParenthesizedExprUp(myPlaceholder.getParent());
        if (parent instanceof PsiConditionalExpression) {
          PsiConditionalExpression ternary = (PsiConditionalExpression)parent;
          if (PsiTreeUtil.isAncestor(ternary.getCondition(), myPlaceholder, false)) {
            myPlaceholder = ternary;
            PsiType type = ternary.getType();
            PsiExpression thenExpression = ternary.getThenExpression();
            PsiExpression elseExpression = ternary.getElseExpression();
            if (type != null && thenExpression != null && elseExpression != null) {
              return condition.toPlain(type.getCanonicalText(), thenExpression.getText(), elseExpression.getText());
            }
          }
        }
      }
      return condition;
    }

    @NotNull
    private ConditionalExpression tryUnwrapOptional(ConditionalExpression.Optional condition, Predicate<PsiElement> predicate) {
      if (myPlaceholder instanceof PsiExpression) {
        PsiMethodCallExpression call = ExpressionUtils.getCallForQualifier((PsiExpression)myPlaceholder);
        if (call != null && !(call.getParent() instanceof PsiExpressionStatement)) {
          String name = call.getMethodExpression().getReferenceName();
          PsiExpression[] args = call.getArgumentList().getExpressions();
          if (args.length == 0 && "isPresent".equals(name)) {
            myPlaceholder = call;
            return new ConditionalExpression.Boolean(condition.getCondition(), false);
          }
          if (args.length == 1) {
            String absentExpression = null;
            if ("orElse".equals(name)) {
              absentExpression = args[0].getText();
            }
            else if ("orElseGet".equals(name) && predicate.test(call)) {
              FunctionHelper helper = FunctionHelper.create(args[0], 0);
              if (helper != null) {
                helper.transform(this);
                absentExpression = helper.getText();
              }
            }
            if (absentExpression != null) {
              myPlaceholder = call;
              return condition.unwrap(absentExpression);
            }
          }
        }
      }
      return condition;
    }

    public Project getProject() {
      return myStatement.getProject();
    }

    public PsiExpression createExpression(String text) {
      return myFactory.createExpressionFromText(text, myStatement);
    }

    public PsiType createType(String text) {
      return myFactory.createTypeFromText(text, myStatement);
    }
  }

  static class OperationRecord {
    Operation myOperation;
    StreamVariable myInVar, myOutVar;
  }
}
