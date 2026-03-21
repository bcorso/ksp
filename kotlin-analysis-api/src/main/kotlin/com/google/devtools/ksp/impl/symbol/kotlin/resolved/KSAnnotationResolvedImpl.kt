package com.google.devtools.ksp.impl.symbol.kotlin.resolved

import com.google.devtools.ksp.common.IdKeyPair
import com.google.devtools.ksp.common.KSObjectCache
import com.google.devtools.ksp.common.impl.KSNameImpl
import com.google.devtools.ksp.impl.symbol.java.JavaAnnotationInfo
import com.google.devtools.ksp.impl.symbol.java.KSValueArgumentLiteImpl
import com.google.devtools.ksp.impl.symbol.kotlin.*
import com.google.devtools.ksp.symbol.AnnotationUseSiteTarget
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.KSVisitor
import com.google.devtools.ksp.symbol.Location
import com.google.devtools.ksp.symbol.NonExistLocation
import com.google.devtools.ksp.symbol.Origin
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.*
import org.jetbrains.kotlin.psi.KtFile

class KSAnnotationResolvedImpl private constructor(
    private val annotationApplication: KaAnnotation,
    override val parent: KSNode?,
    override val origin: Origin,
) : KSAnnotation {
    companion object : KSObjectCache<IdKeyPair<KaAnnotation, KSNode?>, KSAnnotationResolvedImpl>() {
        fun getCached(annotationApplication: KaAnnotation, parent: KSNode? = null, origin: Origin? = parent?.origin) =
            cache.getOrPut(IdKeyPair(annotationApplication, parent)) {
                KSAnnotationResolvedImpl(annotationApplication, parent, origin ?: Origin.SYNTHETIC)
            }
    }

    override val annotationType: KSTypeReference by lazy {
        KSTypeReferenceResolvedImpl.getCached(annotationInfo.kaType, parent = this@KSAnnotationResolvedImpl)
    }

    private val annotationInfo by lazy {
        JavaAnnotationInfo.getCached(annotationApplication.classId!!)
    }
    override val arguments: List<KSValueArgument> by lazy {
        val presentArgs = annotationApplication.arguments.map { arg ->
            val argOrigin = if (origin == Origin.SYNTHETIC) {
                arg.expression.sourcePsi?.containingFile?.let { containingFile ->
                    if (containingFile is KtFile) Origin.KOTLIN else Origin.JAVA
                } ?: Origin.JAVA_LIB // FIXME: how to tell from KOTLIN_LIB?
            } else origin
            KSValueArgumentImpl.getCached(arg, this, argOrigin)
        }
        val presentNames = presentArgs.mapNotNullTo(mutableSetOf()) { it.name }
        presentArgs + defaultArguments
            .filter { it.name != null }
            .filter { it.name !in presentNames }
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
        KSNameImpl.getCached(annotationApplication.classId!!.shortClassName.asString())
    }

    override val useSiteTarget: AnnotationUseSiteTarget? by lazy {
        // Do not use compiler hard-coded use-site target.
        // FIXME: use origin after it is fixed.
        if (parent?.origin == Origin.KOTLIN_LIB || parent?.origin == Origin.JAVA_LIB)
            return@lazy null

        when (annotationApplication.useSiteTarget) {
            null -> null
            FILE -> AnnotationUseSiteTarget.FILE
            PROPERTY -> AnnotationUseSiteTarget.PROPERTY
            FIELD -> AnnotationUseSiteTarget.FIELD
            PROPERTY_GETTER -> AnnotationUseSiteTarget.GET
            PROPERTY_SETTER -> AnnotationUseSiteTarget.SET
            RECEIVER -> AnnotationUseSiteTarget.RECEIVER
            CONSTRUCTOR_PARAMETER -> AnnotationUseSiteTarget.PARAM
            SETTER_PARAMETER -> AnnotationUseSiteTarget.SETPARAM
            PROPERTY_DELEGATE_FIELD -> AnnotationUseSiteTarget.DELEGATE
            ALL -> AnnotationUseSiteTarget.ALL
        }
    }

    override val location: Location by lazy {
        NonExistLocation
    }

    override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R {
        return visitor.visitAnnotation(this, data)
    }

    override fun toString(): String {
        return "@${shortName.asString()}"
    }
}
