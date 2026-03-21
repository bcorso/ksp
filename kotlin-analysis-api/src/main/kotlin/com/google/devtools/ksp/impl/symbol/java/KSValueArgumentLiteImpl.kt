package com.google.devtools.ksp.impl.symbol.java

import com.google.devtools.ksp.common.KSObjectCache
import com.google.devtools.ksp.common.impl.KSNameImpl
import com.google.devtools.ksp.impl.symbol.kotlin.AbstractKSValueArgumentImpl
import com.google.devtools.ksp.impl.symbol.kotlin.analyze
import com.google.devtools.ksp.impl.symbol.kotlin.getDefaultValue
import com.google.devtools.ksp.impl.symbol.kotlin.toKtClassSymbol
import com.google.devtools.ksp.impl.symbol.kotlin.toLocation
import com.google.devtools.ksp.symbol.*
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiAnnotationMethod
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.impl.base.annotations.KaBaseNamedAnnotationValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId

class KSValueArgumentLiteImpl internal constructor(
    private val info: JavaAnnotationValueInfo,
    override val parent: KSNode,
    override val origin: Origin,
) : AbstractKSValueArgumentImpl() {
    constructor(name: KSName?, value: Any?, parent: KSNode, origin: Origin, location: Location): this(
        info = JavaAnnotationValueInfo(name, value, location),
        parent = parent,
        origin = origin,
    )

    constructor(
        name: KSName,
        psiValue: PsiAnnotationMemberValue?,
        parent: KSNode,
        origin: Origin,
    ): this(JavaAnnotationValueInfo(name, psiValue), parent, origin)

    override val name: KSName? get() =  info.name

    override val value: Any? get() = info.value

    override val location: Location get() = info.location

    override val isSpread: Boolean = false

    override val annotations: Sequence<KSAnnotation> = emptySequence()
}

internal class JavaAnnotationInfo(val classId: ClassId) {
    companion object : KSObjectCache<ClassId, JavaAnnotationInfo>() {
        fun getCached(classId: ClassId): JavaAnnotationInfo = cache.getOrPut(classId) { JavaAnnotationInfo(classId) }

        fun getCached(psi: PsiAnnotation): JavaAnnotationInfo {
            fun PsiClass.fqn(): String? {
                val parent = containingClass?.fqn()
                    ?: return qualifiedName?.replace('.', '/')
                if (name == null) return null
                return "$parent.$name"
            }
            // Resolving PSI locally is cheap, no AA overhead.
            val resolved = psi.resolveAnnotationType()
            val fqn = resolved?.fqn() ?: "__KSP_unresolved_${psi.qualifiedName}"
            return getCached(ClassId.fromString(fqn))
        }
    }

    val kaType: KaType by lazy {
        analyze { buildClassType(classId) }
    }

    @OptIn(KaImplementationDetail::class)
    val kotlinDefaultArgs: List<KaBaseNamedAnnotationValue> get() = cachedInfo.kotlinDefaultArgs

    val javaDefaultArgs: List<JavaAnnotationValueInfo> get() = cachedInfo.javaDefaultArgs

    @OptIn(KaImplementationDetail::class)
    private val cachedInfo by lazy {
        analyze {
            val symbol = classId.toKtClassSymbol() ?: return@analyze CachedInfo(emptyList(), emptyList())
            val isJavaOrigin = symbol.origin == KaSymbolOrigin.JAVA_SOURCE || symbol.origin == KaSymbolOrigin.JAVA_LIBRARY
            if (isJavaOrigin && symbol.psi is PsiClass) {
                val psiClass = symbol.psi as PsiClass
                val methods = psiClass.methods.filterIsInstance<PsiAnnotationMethod>()
                val javaArgs = methods.mapNotNull { annoMethod ->
                    annoMethod.defaultValue?.let { psiValue ->
                        JavaAnnotationValueInfo(KSNameImpl.getCached(annoMethod.name), psiValue)
                    }
                }
                CachedInfo(emptyList(), javaArgs)
            } else {
                val constructor = symbol.declaredMemberScope.constructors.singleOrNull()
                val kotlinArgs = constructor?.valueParameters?.mapNotNull { valueParameterSymbol ->
                    val constantValue = valueParameterSymbol.getDefaultValue() ?: return@mapNotNull null
                    KaBaseNamedAnnotationValue(valueParameterSymbol.name, constantValue)
                } ?: emptyList()
                CachedInfo(kotlinArgs, emptyList())
            }
        }
    }

    private data class CachedInfo @OptIn(KaImplementationDetail::class) constructor(
        val kotlinDefaultArgs: List<KaBaseNamedAnnotationValue>,
        val javaDefaultArgs: List<JavaAnnotationValueInfo>,
    )
}

internal class JavaAnnotationValueInfo(
    val name: KSName?,
    private val valueProvider: () -> Any?,
    private val locationProvider: () -> Location,
) {
    constructor(name: KSName?, value: Any?, location: Location): this(name, { value }, { location })

    constructor(name: KSName?, psiValue: PsiAnnotationMemberValue?): this(
        name = name,
        valueProvider = {
            when (psiValue) {
                null -> null
                is PsiArrayInitializerMemberValue -> psiValue.initializers.map { calcValue(it) }
                else -> calcValue(psiValue)
            }
        },
        locationProvider = { psiValue.toLocation() },
    )

    val location by lazy {
        locationProvider()
    }
    val value by lazy {
        valueProvider()
    }
}
