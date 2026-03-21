package com.google.devtools.ksp.impl.symbol.java

import com.google.devtools.ksp.common.KSObjectCache
import com.google.devtools.ksp.common.impl.KSNameImpl
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.impl.ResolverAAImpl
import com.google.devtools.ksp.impl.symbol.kotlin.KSClassDeclarationEnumEntryImpl
import com.google.devtools.ksp.impl.symbol.kotlin.KSErrorType
import com.google.devtools.ksp.impl.symbol.kotlin.KSValueArgumentImpl
import com.google.devtools.ksp.impl.symbol.kotlin.analyze
import com.google.devtools.ksp.impl.symbol.kotlin.classifierSymbol
import com.google.devtools.ksp.impl.symbol.kotlin.resolved.KSTypeReferenceResolvedImpl
import com.google.devtools.ksp.impl.symbol.kotlin.toLocation
import com.google.devtools.ksp.symbol.AnnotationUseSiteTarget
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.KSVisitor
import com.google.devtools.ksp.symbol.Location
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance
import com.intellij.lang.jvm.JvmClassKind
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralValue
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiType
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.name.ClassId

class KSAnnotationJavaImpl private constructor(private val psi: PsiAnnotation, override val parent: KSNode?) :
    KSAnnotation {
    companion object : KSObjectCache<PsiAnnotation, KSAnnotationJavaImpl>() {
        fun getCached(psi: PsiAnnotation, parent: KSNode?) =
            KSAnnotationJavaImpl.cache.getOrPut(psi) { KSAnnotationJavaImpl(psi, parent) }
    }

    private val annotationInfo by lazy {
        JavaAnnotationInfo.getCached(psi)
    }

    override val annotationType: KSTypeReference by lazy {
        // TODO: replace with psi based implementation once `PsiType -> KtType` is supported in AA.
        KSTypeReferenceResolvedImpl.getCached(annotationInfo.kaType, this)
    }

    val annotationConstructor by lazy {
        fun PsiClass.fqn(): String? {
            val parent = containingClass?.fqn()
                ?: return qualifiedName?.replace('.', '/')
            if (name == null)
                return null
            return "$parent.$name"
        }
        analyze {
            val resolved = psi.resolveAnnotationType()
            val fqn = resolved?.fqn() ?: "__KSP_unresolved_${psi.qualifiedName}"
            val classId = ClassId.fromString(fqn)
            val type = buildClassType(classId)
            (type.classifierSymbol() as? KaClassSymbol)?.declaredMemberScope?.constructors?.singleOrNull()
        }
    }

    override val arguments: List<KSValueArgument> by lazy {
        val argOrigin = if (psi.containingFile?.fileType?.isBinary == true) Origin.JAVA_LIB else Origin.JAVA
        val presentArgs = psi.parameterList.attributes.mapIndexed { index, it ->
            val name = it.name ?: annotationConstructor?.valueParameters?.getOrNull(index)?.name?.asString() ?: "value"
            KSValueArgumentLiteImpl(KSNameImpl.getCached(name), it.value, this, argOrigin)
        }
        val presentValueArgumentNames = presentArgs.mapNotNullTo(mutableSetOf()) { it.name }
        presentArgs + defaultArguments
            .filter { it.name != null }
            .filter { it.name !in presentValueArgumentNames }
    }

    @OptIn(KaImplementationDetail::class)
    override val defaultArguments: List<KSValueArgument> by lazy {
        if (annotationInfo.javaDefaultArgs.isNotEmpty()) {
            annotationInfo.javaDefaultArgs.map {
                KSValueArgumentLiteImpl(it, this, Origin.SYNTHETIC)
            }
        } else {
            annotationInfo.kotlinDefaultArgs.map {
                KSValueArgumentImpl.getCached(it, this, Origin.SYNTHETIC)
            }
        }
    }

    override val shortName: KSName by lazy {
        KSNameImpl.getCached(psi.qualifiedName!!.split(".").last())
    }

    override val useSiteTarget: AnnotationUseSiteTarget? = null

    override val origin: Origin = if (parent == null) Origin.SYNTHETIC else Origin.JAVA

    override val location: Location
        get() = psi.toLocation()

    override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R {
        return visitor.visitAnnotation(this, data)
    }

    override fun toString(): String {
        return "@${shortName.asString()}"
    }
}

fun calcValue(value: PsiAnnotationMemberValue?): Any? {
    if (value is PsiAnnotation) {
        return KSAnnotationJavaImpl.getCached(value, null)
    }
    val result = when (value) {
        is PsiReference -> value.resolve()?.let { resolved ->
            JavaPsiFacade.getInstance(value.project).constantEvaluationHelper.computeConstantExpression(value)
                ?: resolved
        }

        else -> value?.let {
            JavaPsiFacade.getInstance(value.project).constantEvaluationHelper.computeConstantExpression(value)
        }
    }
    return when (result) {
        is PsiPrimitiveType -> {
            result.boxedTypeName?.let {
                ResolverAAImpl.instance
                    .getClassDeclarationByName(it)?.asStarProjectedType()
            } ?: KSErrorType(result.boxedTypeName)
        }

        is PsiArrayType -> {
            val componentType = when (val component = result.componentType) {
                is PsiPrimitiveType -> component.boxedTypeName?.let { boxedTypeName ->
                    ResolverAAImpl.instance
                        .getClassDeclarationByName(boxedTypeName)?.asStarProjectedType()
                } ?: KSErrorType(component.boxedTypeName)

                else -> {
                    ResolverAAImpl.instance
                        .getClassDeclarationByName(component.canonicalText)?.asStarProjectedType()
                        ?: KSErrorType(component.canonicalText)
                }
            }
            val componentTypeRef = ResolverAAImpl.instance.createKSTypeReferenceFromKSType(componentType)
            val typeArgs = listOf(ResolverAAImpl.instance.getTypeArgument(componentTypeRef, Variance.INVARIANT))
            ResolverAAImpl.instance
                .getClassDeclarationByName("kotlin.Array")!!.asType(typeArgs)
        }

        is PsiType -> {
            ResolverAAImpl.instance
                .getClassDeclarationByName(result.canonicalText)?.asStarProjectedType()
                ?: KSErrorType(result.canonicalText)
        }

        is PsiLiteralValue -> {
            result.value
        }

        is PsiField -> {
            // manually handle enums as constant expression evaluator does not seem to be resolving them.
            val containingClass = result.containingClass
            @Suppress("UnstableApiUsage")
            if (containingClass?.classKind == JvmClassKind.ENUM) {
                // this is an enum entry
                containingClass.qualifiedName?.let {
                    ResolverAAImpl.instance.getClassDeclarationByName(it)
                }?.declarations?.find {
                    it is KSClassDeclaration && it.classKind == ClassKind.ENUM_ENTRY &&
                        it.simpleName.asString() == result.name
                } as? KSClassDeclarationEnumEntryImpl
            } else {
                null
            }
        }

        else -> result
    }
}
