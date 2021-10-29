package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completions.lang.java.daemon.impl.quickfix.ImportClassFixBase;
import com.tyron.psi.completions.lang.java.earch.PsiShortNamesCache;
import com.tyron.psi.tailtype.TailType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.NullableComputable;
import org.jetbrains.kotlin.com.intellij.openapi.util.NullableLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.util.VolatileNullableLazyValue;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.List;

public class ExpectedTypeInfoImpl implements ExpectedTypeInfo {

    public static final NullableComputable<String> NULL = () -> null;
    @NotNull
    private final PsiType type;
    @NotNull
    private final PsiType defaultType;
    private final int kind;
    @NotNull
    private final TailType myTailType;
    private final PsiMethod myCalledMethod;
    @NotNull private final NullableComputable<String> expectedNameComputable;
    @NotNull private final NullableLazyValue<String> expectedNameLazyValue;

    @Override
    public int getKind() {
        return kind;
    }

    @NotNull
    @Override
    public TailType getTailType() {
        return myTailType;
    }

    public ExpectedTypeInfoImpl(@NotNull PsiType type,
                                @Type int kind,
                                @NotNull PsiType defaultType,
                                @NotNull TailType myTailType,
                                PsiMethod calledMethod,
                                @NotNull NullableComputable<String> expectedName) {
        this.type = type;
        this.kind = kind;

        this.myTailType = myTailType;
        this.defaultType = defaultType;
        myCalledMethod = calledMethod;
        this.expectedNameComputable = expectedName;
        expectedNameLazyValue = new VolatileNullableLazyValue<String>() {
            @Nullable
            @Override
            protected String compute() {
                return expectedNameComputable.compute();
            }
        };

        PsiUtil.ensureValidType(type);
        PsiUtil.ensureValidType(defaultType);
    }

    @Nullable
    public String getExpectedName() {
        return expectedNameLazyValue.getValue();
    }

    @Override
    public PsiMethod getCalledMethod() {
        return myCalledMethod;
    }

    @Override
    @NotNull
    public PsiType getType () {
        return type;
    }

    @Override
    @NotNull
    public PsiType getDefaultType () {
        return defaultType;
    }

    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof ExpectedTypeInfoImpl)) return false;

        final ExpectedTypeInfoImpl that = (ExpectedTypeInfoImpl)o;

        if (kind != that.kind) return false;
        if (!defaultType.equals(that.defaultType)) return false;
        if (!myTailType.equals(that.myTailType)) return false;
        if (!type.equals(that.type)) return false;

        return true;
    }

    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + defaultType.hashCode();
        result = 31 * result + kind;
        result = 31 * result + myTailType.hashCode();
        return result;
    }

    @Override
    public boolean equals(ExpectedTypeInfo obj) {
        return equals((Object)obj);
    }

    public String toString() {
        return "ExpectedTypeInfo[type='" + type + "' kind='" + kind + "']";
    }

    @Override
    public ExpectedTypeInfo[] intersect(@NotNull ExpectedTypeInfo info) {
        ExpectedTypeInfoImpl info1 = (ExpectedTypeInfoImpl)info;

        if (kind == TYPE_STRICTLY) {
            if (info1.kind == TYPE_STRICTLY) {
                if (info1.type.equals(type)) return new ExpectedTypeInfoImpl[] {this};
            }
            else {
                return info1.intersect(this);
            }
        }
        else if (kind == TYPE_OR_SUBTYPE) {
            if (info1.kind == TYPE_STRICTLY) {
                if (type.isAssignableFrom(info1.type)) return new ExpectedTypeInfoImpl[] {info1};
            }
            else if (info1.kind == TYPE_OR_SUBTYPE) {
                PsiType otherType = info1.type;
                if (type.isAssignableFrom(otherType)) return new ExpectedTypeInfoImpl[] {info1};
                else if (otherType.isAssignableFrom(type)) return new ExpectedTypeInfoImpl[] {this};
            }
            else {
                return info1.intersect(this);
            }
        }
        else if (kind == TYPE_OR_SUPERTYPE) {
            if (info1.kind == TYPE_STRICTLY) {
                if (info1.type.isAssignableFrom(type)) return new ExpectedTypeInfoImpl[] {info1};
            }
            else if (info1.kind == TYPE_OR_SUBTYPE) {
                if (info1.type.isAssignableFrom(type)) return new ExpectedTypeInfoImpl[] {this};
            }
            else if (info1.kind == TYPE_OR_SUPERTYPE) {
                PsiType otherType = info1.type;
                if (type.isAssignableFrom(otherType)) return new ExpectedTypeInfoImpl[] {this};
                else if (otherType.isAssignableFrom(type)) return new ExpectedTypeInfoImpl[] {info1};
            }
            else {
                return info1.intersect(this);
            }
        }


        //todo: the following cases are not implemented: SUPERxSUB, SUBxSUPER

        return ExpectedTypeInfo.EMPTY_ARRAY;
    }

    @NotNull
    ExpectedTypeInfoImpl fixUnresolvedTypes(@NotNull PsiElement context) {
        PsiType resolvedType = fixUnresolvedType(context, type);
        PsiType resolvedDefaultType = fixUnresolvedType(context, defaultType);
        if (resolvedType != type || resolvedDefaultType != defaultType) {
            return new ExpectedTypeInfoImpl(resolvedType, kind, resolvedDefaultType, myTailType, myCalledMethod, expectedNameComputable);
        }
        return this;
    }

    @NotNull
    private static PsiType fixUnresolvedType(@NotNull PsiElement context, @NotNull PsiType type) {
        if (type instanceof PsiClassType && ((PsiClassType)type).resolve() == null) {
            String className = ((PsiClassType)type).getClassName();
            int typeParamCount = ((PsiClassType)type).getParameterCount();
            Project project = context.getProject();
            PsiResolveHelper helper = PsiResolveHelper.SERVICE.getInstance(project);
            List<PsiClass> suitableClasses = ContainerUtil.filter(
                    PsiShortNamesCache.getInstance(project).getClassesByName(className, context.getResolveScope()),
                    c -> (typeParamCount == 0 || c.hasTypeParameters()) &&
                            helper.isAccessible(c, context, null) &&
                            ImportClassFixBase.qualifiedNameAllowsAutoImport(context.getContainingFile(), c));
            if (suitableClasses.size() == 1) {
                return PsiElementFactory.getInstance(project).createType(suitableClasses.get(0), ((PsiClassType)type).getParameters());
            }
        }
        return type;
    }

}
