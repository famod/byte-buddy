package com.blogspot.mydailyjava.bytebuddy.instrumentation.type.auxiliary;

import com.blogspot.mydailyjava.bytebuddy.ByteBuddy;
import com.blogspot.mydailyjava.bytebuddy.ClassFormatVersion;
import com.blogspot.mydailyjava.bytebuddy.dynamic.DynamicType;
import com.blogspot.mydailyjava.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.Instrumentation;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.ModifierContributor;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.field.FieldDescription;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.field.FieldList;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.MethodDescription;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.ByteCodeAppender;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.Duplication;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.StackManipulation;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.TypeCreation;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.assign.Assigner;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.assign.primitive.PrimitiveTypeAwareAssigner;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.assign.primitive.VoidAwareAssigner;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.assign.reference.ReferenceTypeAwareAssigner;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.member.FieldAccess;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.member.MethodInvocation;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.member.MethodReturn;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.member.MethodVariableAccess;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.type.InstrumentedType;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.type.TypeDescription;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.type.TypeList;
import com.blogspot.mydailyjava.bytebuddy.modifier.Visibility;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.blogspot.mydailyjava.bytebuddy.instrumentation.method.matcher.MethodMatchers.isConstructor;

/**
 * A method call proxy represents a class that is compiled against a particular method which can then be called whenever
 * either its {@link java.util.concurrent.Callable#call()} or {@link Runnable#run()} method is called where the method
 * call proxy implements both interfaces.
 * <p/>
 * In order to do so, the method call proxy instances are constructed by providing all the necessary information for
 * calling a particular method:
 * <ol>
 * <li>If the target method is not {@code static}, the first argument should be an instance on which the method is called.</li>
 * <li>All arguments for the called method in the order in which they are required.</li>
 * </ol>
 */
public class MethodCallProxy implements AuxiliaryType {

    /**
     * A stack manipulation that creates a {@link com.blogspot.mydailyjava.bytebuddy.instrumentation.type.auxiliary.MethodCallProxy}
     * for a given method an pushes such an object onto the call stack. For this purpose, all arguments of the proxied method
     * are loaded onto the stack what is only possible if this instance is used from a method with an identical signature such
     * as the target method itself.
     */
    public static class AssignableSignatureCall implements StackManipulation {

        private final MethodDescription targetMethod;

        /**
         * Creates an operand stack assignment that creates a
         * {@link com.blogspot.mydailyjava.bytebuddy.instrumentation.type.auxiliary.MethodCallProxy} for the
         * {@code targetMethod} and pushes this proxy object onto the stack.
         *
         * @param targetMethod The target method for which the proxy should be created.
         */
        public AssignableSignatureCall(MethodDescription targetMethod) {
            this.targetMethod = targetMethod;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public Size apply(MethodVisitor methodVisitor, Instrumentation.Context instrumentationContext) {
            TypeDescription auxiliaryType = instrumentationContext.register(new MethodCallProxy(targetMethod));
            return new Compound(
                    TypeCreation.forType(auxiliaryType),
                    Duplication.SINGLE,
                    MethodVariableAccess.loadThisAndArguments(targetMethod),
                    MethodInvocation.invoke(auxiliaryType.getDeclaredMethods().filter(isConstructor()).getOnly())
            ).apply(methodVisitor, instrumentationContext);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && targetMethod.equals(((AssignableSignatureCall) other).targetMethod);
        }

        @Override
        public int hashCode() {
            return targetMethod.hashCode();
        }

        @Override
        public String toString() {
            return "AssignableSignatureCall{targetMethod=" + targetMethod + '}';
        }
    }

    private static final String FIELD_NAME_PREFIX = "argument";

    private static class MethodCall implements Instrumentation {

        private class Appender implements ByteCodeAppender {

            private final TypeDescription instrumentedType;

            private Appender(TypeDescription instrumentedType) {
                this.instrumentedType = instrumentedType;
            }

            @Override
            public boolean appendsCode() {
                return true;
            }

            @Override
            public Size apply(MethodVisitor methodVisitor, Context instrumentationContext, MethodDescription instrumentedMethod) {
                StackManipulation thisReference = MethodVariableAccess.forType(instrumentedType).loadFromIndex(0);
                FieldList fieldList = instrumentedType.getDeclaredFields();
                StackManipulation[] fieldLoading = new StackManipulation[fieldList.size()];
                int index = 0;
                for (FieldDescription fieldDescription : fieldList) {
                    fieldLoading[index++] = new StackManipulation.Compound(thisReference, FieldAccess.forField(fieldDescription).getter());
                }
                StackManipulation.Size stackSize = new StackManipulation.Compound(
                        new StackManipulation.Compound(fieldLoading),
                        MethodInvocation.invoke(accessorMethod),
                        assigner.assign(accessorMethod.getReturnType(), instrumentedMethod.getReturnType(), false),
                        MethodReturn.returning(instrumentedMethod.getReturnType())
                ).apply(methodVisitor, instrumentationContext);
                return new Size(stackSize.getMaximalSize(), instrumentedMethod.getStackSize());
            }
        }

        private final MethodDescription accessorMethod;
        private final Assigner assigner;

        private MethodCall(MethodDescription accessorMethod, Assigner assigner) {
            this.accessorMethod = accessorMethod;
            this.assigner = assigner;
        }

        @Override
        public InstrumentedType prepare(InstrumentedType instrumentedType) {
            return instrumentedType;
        }

        @Override
        public ByteCodeAppender appender(TypeDescription instrumentedType) {
            return new Appender(instrumentedType);
        }

        @Override
        public boolean equals(Object o) {
            return this == o || !(o == null || getClass() != o.getClass())
                    && accessorMethod.equals(((MethodCall) o).accessorMethod);
        }

        @Override
        public int hashCode() {
            return accessorMethod.hashCode();
        }

        @Override
        public String toString() {
            return "MethodCall{accessorMethod=" + accessorMethod + '}';
        }
    }

    private static class ConstructorCall implements Instrumentation {

        private class Appender implements ByteCodeAppender {

            private final TypeDescription instrumentedType;

            private Appender(TypeDescription instrumentedType) {
                this.instrumentedType = instrumentedType;
            }

            @Override
            public boolean appendsCode() {
                return true;
            }

            @Override
            public Size apply(MethodVisitor methodVisitor, Context instrumentationContext, MethodDescription instrumentedMethod) {
                StackManipulation thisReference = MethodVariableAccess.forType(instrumentedMethod.getDeclaringType()).loadFromIndex(0);
                FieldList fieldList = instrumentedType.getDeclaredFields();
                StackManipulation[] fieldLoading = new StackManipulation[fieldList.size()];
                int index = 0;
                for (FieldDescription fieldDescription : fieldList) {
                    fieldLoading[index] = new StackManipulation.Compound(
                            thisReference,
                            MethodVariableAccess.forType(fieldDescription.getFieldType()).loadFromIndex(instrumentedMethod.getParameterOffset(index)),
                            FieldAccess.forField(fieldDescription).putter()
                    );
                    index++;
                }
                StackManipulation.Size stackSize = new StackManipulation.Compound(
                        thisReference,
                        MethodInvocation.invoke(instrumentedType.getSupertype().getDeclaredMethods().filter(isConstructor()).getOnly()),
                        new StackManipulation.Compound(fieldLoading),
                        MethodReturn.VOID
                ).apply(methodVisitor, instrumentationContext);
                return new Size(stackSize.getMaximalSize(), instrumentedMethod.getStackSize());
            }
        }

        @Override
        public InstrumentedType prepare(InstrumentedType instrumentedType) {
            return instrumentedType;
        }

        @Override
        public ByteCodeAppender appender(TypeDescription instrumentedType) {
            return new Appender(instrumentedType);
        }
    }

    private final MethodDescription targetMethod;
    private final Assigner assigner;

    /**
     * Creates a new method call proxy for a given method and uses a default assigner for assigning the method's return
     * value to either the {@link java.util.concurrent.Callable#call()} or {@link Runnable#run()} method returns.
     *
     * @param targetMethod The method to be proxied.
     */
    public MethodCallProxy(MethodDescription targetMethod) {
        this(targetMethod, new VoidAwareAssigner(new PrimitiveTypeAwareAssigner(ReferenceTypeAwareAssigner.INSTANCE), true));
    }

    /**
     * Creates a new method call proxy for a given method.
     *
     * @param targetMethod The method to be proxied.
     * @param assigner     An assigner for assigning the target method's return value to either the
     *                     {@link java.util.concurrent.Callable#call()} or {@link Runnable#run()}} methods' return
     *                     values.
     */
    public MethodCallProxy(MethodDescription targetMethod, Assigner assigner) {
        this.targetMethod = targetMethod;
        this.assigner = assigner;
    }

    @Override
    public DynamicType make(String auxiliaryTypeName,
                            ClassFormatVersion classFormatVersion,
                            MethodAccessorFactory methodAccessorFactory) {
        MethodDescription accessorMethod = methodAccessorFactory.requireAccessorMethodFor(targetMethod);
        LinkedHashMap<String, TypeDescription> parameterFields = extractFields(accessorMethod);
        DynamicType.Builder<?> builder = new ByteBuddy(classFormatVersion)
                .subclass(Object.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                .name(auxiliaryTypeName)
                .modifiers(DEFAULT_TYPE_MODIFIER.toArray(new ModifierContributor.ForType[DEFAULT_TYPE_MODIFIER.size()]))
                .implement(Runnable.class).intercept(new MethodCall(accessorMethod, assigner))
                .implement(Callable.class).intercept(new MethodCall(accessorMethod, assigner))
                .defineConstructorDescriptive(new ArrayList<TypeDescription>(parameterFields.values()))
                .intercept(new ConstructorCall());
        for (Map.Entry<String, TypeDescription> field : parameterFields.entrySet()) {
            builder = builder.defineField(field.getKey(), field.getValue(), Visibility.PRIVATE);
        }
        return builder.make();
    }

    private static LinkedHashMap<String, TypeDescription> extractFields(MethodDescription methodDescription) {
        TypeList parameterTypes = methodDescription.getParameterTypes();
        LinkedHashMap<String, TypeDescription> typeDescriptions =
                new LinkedHashMap<String, TypeDescription>((methodDescription.isStatic() ? 0 : 1) + parameterTypes.size());
        int currentIndex = 0;
        if (!methodDescription.isStatic()) {
            typeDescriptions.put(fieldName(currentIndex++), methodDescription.getDeclaringType());
        }
        for (TypeDescription parameterType : parameterTypes) {
            typeDescriptions.put(fieldName(currentIndex++), parameterType);
        }
        return typeDescriptions;
    }

    private static String fieldName(int index) {
        return String.format("%s%d", FIELD_NAME_PREFIX, index);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || !(other == null || getClass() != other.getClass())
                && assigner.equals(((MethodCallProxy) other).assigner)
                && targetMethod.equals(((MethodCallProxy) other).targetMethod);
    }

    @Override
    public int hashCode() {
        return 31 * targetMethod.hashCode() + assigner.hashCode();
    }

    @Override
    public String toString() {
        return "MethodCallProxy{" +
                "targetMethod=" + targetMethod +
                ", assigner=" + assigner +
                '}';
    }
}
