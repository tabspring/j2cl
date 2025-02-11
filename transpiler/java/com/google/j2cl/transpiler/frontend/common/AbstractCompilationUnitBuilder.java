/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.frontend.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.FormatMethod;
import com.google.j2cl.common.InternalCompilerError;
import com.google.j2cl.common.SourcePosition;
import com.google.j2cl.transpiler.ast.ArrayTypeDescriptor;
import com.google.j2cl.transpiler.ast.AstUtils;
import com.google.j2cl.transpiler.ast.BooleanLiteral;
import com.google.j2cl.transpiler.ast.CompilationUnit;
import com.google.j2cl.transpiler.ast.Expression;
import com.google.j2cl.transpiler.ast.FunctionExpression;
import com.google.j2cl.transpiler.ast.JavaScriptConstructorReference;
import com.google.j2cl.transpiler.ast.MethodDescriptor;
import com.google.j2cl.transpiler.ast.MultiExpression;
import com.google.j2cl.transpiler.ast.NewArray;
import com.google.j2cl.transpiler.ast.NewInstance;
import com.google.j2cl.transpiler.ast.NumberLiteral;
import com.google.j2cl.transpiler.ast.ReturnStatement;
import com.google.j2cl.transpiler.ast.Statement;
import com.google.j2cl.transpiler.ast.StringLiteral;
import com.google.j2cl.transpiler.ast.Type;
import com.google.j2cl.transpiler.ast.TypeDescriptor;
import com.google.j2cl.transpiler.ast.Variable;
import com.google.j2cl.transpiler.ast.VariableDeclarationExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Base class for implementing that AST conversion from different front ends. */
public abstract class AbstractCompilationUnitBuilder {

  private final PackageInfoCache packageInfoCache = PackageInfoCache.get();

  /** Type stack to keep track of the lexically enclosing types as they are being created. */
  private final List<Type> typeStack = new ArrayList<>();

  private String currentSourceFile;
  private CompilationUnit currentCompilationUnit;

  /**
   * Sets the JS namespace and whether it defines a null marked scope for a package that is being
   * compiled from source.
   */
  protected void setPackagePropertiesFromSource(
      String packageName, String jsNamespace, boolean isNullMarked) {
    packageInfoCache.setPackageProperties(
        PackageInfoCache.SOURCE_CLASS_PATH_ENTRY, packageName, jsNamespace, isNullMarked);
  }

  protected String getCurrentSourceFile() {
    return currentSourceFile;
  }

  protected void setCurrentSourceFile(String currentSourceFile) {
    this.currentSourceFile = currentSourceFile;
  }

  protected CompilationUnit getCurrentCompilationUnit() {
    return currentCompilationUnit;
  }

  protected void setCurrentCompilationUnit(CompilationUnit currentCompilationUnit) {
    this.currentCompilationUnit = currentCompilationUnit;
  }

  /** Invoke {@code supplier} with {@code type} in the type stack. */
  protected <T> T processEnclosedBy(Type type, Supplier<T> supplier) {
    typeStack.add(type);
    T converted = supplier.get();
    typeStack.remove(typeStack.size() - 1);
    return converted;
  }

  /** Returns the current type. */
  protected Type getCurrentType() {
    return Iterables.getLast(typeStack, null);
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Helpers to synthesize lambdas for method references.
  ////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Creates a lambda from a qualified method reference.
   *
   * <p>
   *
   * <pre>{@code
   * q::m into (par1, ..., parN) -> q.m(par1, ..., parN) or
   *           (let $q = q, (par1, ..., parN) -> $q.m(par1, ..., parN)
   * }</pre>
   *
   * <p>depending on whether the qualifier can be evaluated inside the functional expression
   * preserving the original semantics.
   */
  protected static Expression createMethodReferenceLambda(
      SourcePosition sourcePosition,
      Expression qualifier,
      MethodDescriptor referencedMethodDescriptor,
      TypeDescriptor expressionTypeDescriptor,
      MethodDescriptor functionalMethodDescriptor) {
    List<Expression> result = new ArrayList<>();

    if (qualifier != null && !qualifier.isEffectivelyInvariant()) {
      // The semantics require that the qualifier be evaluated in the context where the method
      // reference appears, so here we introduce a temporary variable to store the evaluated
      // qualifier.
      Variable variable =
          Variable.newBuilder()
              .setFinal(true)
              .setName("$$q")
              .setTypeDescriptor(qualifier.getTypeDescriptor())
              .build();
      // Declare the temporary variable and initialize to the evaluated qualifier.
      result.add(
          VariableDeclarationExpression.newBuilder()
              .addVariableDeclaration(variable, qualifier)
              .build());
      // Use the newly introduced variable as a qualifier when forwarding the call within the
      // lambda expression.
      qualifier = variable.createReference();
    }

    result.add(
        createForwardingFunctionExpression(
            sourcePosition,
            expressionTypeDescriptor,
            functionalMethodDescriptor,
            qualifier,
            referencedMethodDescriptor,
            false));

    return MultiExpression.newBuilder().setExpressions(result).build();
  }

  /**
   * Creates a FunctionExpression described by {@code functionalMethodDescriptor} that forwards to
   * {@code targetMethodDescriptor}.
   */
  protected static FunctionExpression createForwardingFunctionExpression(
      SourcePosition sourcePosition,
      TypeDescriptor expressionTypeDescriptor,
      MethodDescriptor functionalMethodDescriptor,
      Expression qualifier,
      MethodDescriptor targetMethodDescriptor,
      boolean isStaticDispatch) {

    List<Variable> parameters =
        AstUtils.createParameterVariables(functionalMethodDescriptor.getParameterTypeDescriptors());

    List<Variable> forwardingParameters = parameters;
    if (!targetMethodDescriptor.isStatic()
        && (qualifier == null || qualifier instanceof JavaScriptConstructorReference)) {
      // The qualifier for the instance method becomes the first parameter. Method references to
      // instance methods without an explicit qualifier use the first parameter in the functional
      // interface as the qualifier for the method call.
      checkArgument(
          parameters.size() == targetMethodDescriptor.getParameterTypeDescriptors().size() + 1
              || (parameters.size() >= targetMethodDescriptor.getParameterTypeDescriptors().size()
                  && targetMethodDescriptor.isVarargs()));
      qualifier = parameters.get(0).createReference();
      forwardingParameters = parameters.subList(1, parameters.size());
    }

    Statement forwardingStatement =
        AstUtils.createForwardingStatement(
            sourcePosition,
            qualifier,
            targetMethodDescriptor,
            isStaticDispatch,
            forwardingParameters.stream().map(Variable::createReference).collect(toImmutableList()),
            functionalMethodDescriptor.getReturnTypeDescriptor());
    return FunctionExpression.newBuilder()
        .setTypeDescriptor(expressionTypeDescriptor)
        .setParameters(parameters)
        .setStatements(forwardingStatement)
        .setSourcePosition(sourcePosition)
        .build();
  }

  /**
   * Creates a class instantiation lambda from a method reference.
   *
   * <p>
   *
   * <pre>{@code
   * A:new into (par1, ..., parN) -> new A(par1, ..., parN) or
   *            (par1, ..., parN) -> B.this.new A(par1, ..., parN)
   * }</pre>
   */
  protected Expression createInstantiationLambda(
      MethodDescriptor functionalMethodDescriptor,
      MethodDescriptor targetConstructorMethodDescriptor,
      Expression qualifier,
      SourcePosition sourcePosition) {

    List<Variable> parameters =
        AstUtils.createParameterVariables(functionalMethodDescriptor.getParameterTypeDescriptors());
    checkArgument(
        targetConstructorMethodDescriptor.getParameterTypeDescriptors().size()
            == parameters.size());

    NewInstance instantiation =
        NewInstance.Builder.from(targetConstructorMethodDescriptor)
            .setQualifier(qualifier)
            .setArguments(
                parameters.stream().map(Variable::createReference).collect(toImmutableList()))
            .build();

    return FunctionExpression.newBuilder()
        .setTypeDescriptor(functionalMethodDescriptor.getEnclosingTypeDescriptor())
        .setParameters(parameters)
        .setStatements(
            ReturnStatement.newBuilder()
                .setExpression(instantiation)
                .setSourcePosition(sourcePosition)
                .build())
        .setSourcePosition(sourcePosition)
        .build();
  }

  /**
   * Creates a lambda that implements an array creation method reference.
   *
   * <p>
   *
   * <pre>{@code convert A[]::new into (size) -> new A[size]} </pre>
   */
  protected static Expression createArrayCreationLambda(
      MethodDescriptor targetFunctionalMethodDescriptor,
      ArrayTypeDescriptor arrayType,
      SourcePosition sourcePosition) {

    // Array creation method references always have exactly one parameter.
    Variable parameter =
        Iterables.getOnlyElement(
            AstUtils.createParameterVariables(
                targetFunctionalMethodDescriptor.getParameterTypeDescriptors()));

    // The size of the array is the only parameter in the implemented function. It's legal for
    // the source to provide only one dimension parameter to to create a multidimensional array
    // but our AST expects NewArray nodes to provide an expression for each dimension in the
    // array type, hence the missing dimensions are padded with null.
    ImmutableList<Expression> dimensionExpressions =
        ImmutableList.<Expression>builder()
            .add(parameter.createReference())
            .addAll(AstUtils.createListOfNullValues(arrayType.getDimensions() - 1))
            .build();

    return FunctionExpression.newBuilder()
        .setTypeDescriptor(targetFunctionalMethodDescriptor.getEnclosingTypeDescriptor())
        .setParameters(parameter)
        .setStatements(
            ReturnStatement.newBuilder()
                .setExpression(
                    NewArray.newBuilder()
                        .setTypeDescriptor(arrayType)
                        .setDimensionExpressions(dimensionExpressions)
                        .build())
                .setSourcePosition(sourcePosition)
                .build())
        .setSourcePosition(sourcePosition)
        .build();
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // General helpers.
  ////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Creates temporary variables for a resource that is declared outside of the try-catch statement.
   */
  protected static VariableDeclarationExpression toResource(Expression expression) {
    if (expression instanceof VariableDeclarationExpression) {
      return (VariableDeclarationExpression) expression;
    }

    // Create temporary variables for resources declared outside of the try statement.
    return VariableDeclarationExpression.newBuilder()
        .addVariableDeclaration(
            Variable.newBuilder()
                .setName("$resource")
                .setTypeDescriptor(expression.getTypeDescriptor())
                .setFinal(true)
                .build(),
            expression)
        .build();
  }

  protected Expression convertConstantToLiteral(
      Object constantValue, TypeDescriptor typeDescriptor) {
    if (constantValue instanceof Boolean) {
      return (boolean) constantValue ? BooleanLiteral.get(true) : BooleanLiteral.get(false);
    }
    if (constantValue instanceof Number) {
      return new NumberLiteral(typeDescriptor.toUnboxedType(), (Number) constantValue);
    }
    if (constantValue instanceof Character) {
      return NumberLiteral.fromChar((Character) constantValue);
    }
    if (constantValue instanceof String) {
      return new StringLiteral((String) constantValue);
    }
    throw internalCompilerError(
        "Unexpected type for compile time constant: %s", constantValue.getClass().getSimpleName());
  }

  @FormatMethod
  protected Error internalCompilerError(Throwable e, String format, Object... params) {
    return new InternalCompilerError(e, internalCompilerErrorMessage(format, params));
  }

  @FormatMethod
  protected Error internalCompilerError(String format, Object... params) {
    return new InternalCompilerError(internalCompilerErrorMessage(format, params));
  }

  @FormatMethod
  protected String internalCompilerErrorMessage(String format, Object... params) {
    return String.format(format, params) + ", in file: " + currentSourceFile;
  }
}
