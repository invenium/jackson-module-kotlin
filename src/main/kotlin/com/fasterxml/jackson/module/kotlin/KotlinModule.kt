package com.fasterxml.jackson.module.kotlin

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.databind.introspect.*
import com.fasterxml.jackson.databind.module.SimpleModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.*
import kotlin.reflect.KParameter
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.internal.KotlinReflectionInternalError
import kotlin.reflect.jvm.kotlinFunction

private const val metadataFqName = "kotlin.Metadata"

val Class<*>.isKotlinClass: Boolean
	get() {
		return this.declaredConstructors.any {
			this.declaredAnnotations.singleOrNull { it.annotationClass.java.name == metadataFqName } != null
		}
	}

class KotlinModule : SimpleModule(PackageVersion.VERSION) {

	companion object {
		private const val serialVersionUID = 1L
	}

	val requireJsonCreatorAnnotation: Boolean = false

	private val impliedClasses = HashSet<Class<*>>(setOf(
			Pair::class.java,
			Triple::class.java
	))

	override fun setupModule(context: SetupContext) {
		super.setupModule(context)

		fun addMixin(clazz: Class<*>, mixin: Class<*>) {
			impliedClasses.add(clazz)
			context.setMixInAnnotations(clazz, mixin)
		}

		context.appendAnnotationIntrospector(KotlinNamesAnnotationIntrospector(this))

		// ranges
		addMixin(IntRange::class.java, ClosedRangeMixin::class.java)
		addMixin(CharRange::class.java, ClosedRangeMixin::class.java)
		addMixin(LongRange::class.java, ClosedRangeMixin::class.java)
	}
}


internal open class KotlinNamesAnnotationIntrospector(val module: KotlinModule) : NopAnnotationIntrospector() {

	// since 2.4
	override fun findImplicitPropertyName(member: AnnotatedMember): String? {
		if (member is AnnotatedParameter) {
			return findKotlinParameterName(member)
		}
		return null
	}

	@Suppress("UNCHECKED_CAST")
	override fun hasCreatorAnnotation(member: Annotated): Boolean {
		// don't add a JsonCreator to any constructor if one is declared already

		if (member is AnnotatedConstructor) {
			// if has parameters, is a Kotlin class, and the parameters all have parameter annotations, then pretend we have a JsonCreator
			if (member.parameterCount > 0 && member.declaringClass.isKotlinClass) {
				val kClass = (member.declaringClass as Class<Any>).kotlin
				val kConstructor = (member.annotated as Constructor<Any>).kotlinFunction

				if (kConstructor != null) {
					val isPrimaryConstructor = kClass.primaryConstructor == kConstructor ||
							(kClass.primaryConstructor == null && kClass.constructors.size == 1)
					val anyConstructorHasJsonCreator = kClass.constructors.any { constructor ->
						constructor.annotations.any { it.annotationClass.java == JsonCreator::class.java }
					}

					val anyCompanionMethodIsJsonCreator = member.contextClass.rawType.kotlin.companionObject?.declaredFunctions?.any { function ->
						function.annotations.any { it.annotationClass.java == JvmStatic::class.java } &&
								function.annotations.any { it.annotationClass.java == JsonCreator::class.java }
					} ?: false
					val anyStaticMethodIsJsonCreator = member.contextClass.rawType.declaredMethods.any { method ->
						val isStatic = Modifier.isStatic(method.modifiers)
						val isCreator = method.declaredAnnotations.any { it.annotationClass.java == JsonCreator::class.java }
						isStatic && isCreator
					}

					val areAllParametersValid = kConstructor.parameters.size == kConstructor.parameters.count { it.name != null }

					return isPrimaryConstructor && !(anyConstructorHasJsonCreator || anyCompanionMethodIsJsonCreator || anyStaticMethodIsJsonCreator) && areAllParametersValid
				}
			}
		}
		return false
	}

	@Suppress("UNCHECKED_CAST")
	protected fun findKotlinParameterName(param: AnnotatedParameter): String? {
		if (param.declaringClass.isKotlinClass) {
			val member = param.owner.member
			return if (member is Constructor<*>) {
				val ctor = (member as Constructor<Any>)
				val ctorParamCount = ctor.parameterTypes.size
				val ktorParamCount = ctor.kotlinFunction?.parameters?.size ?: 0
				if (ktorParamCount > 0 && ktorParamCount == ctorParamCount) {
					ctor.kotlinFunction?.parameters?.get(param.index)?.name
				} else {
					null
				}
			} else if (member is Method) {
				try {
					val temp = member.kotlinFunction

					val firstParamKind = temp?.parameters?.firstOrNull()?.kind
					val idx = if (firstParamKind != KParameter.Kind.VALUE) param.index + 1 else param.index
					val paramCount = temp?.parameters?.size ?: 0
					if (paramCount > idx) {
						temp?.parameters?.get(idx)?.name
					} else {
						null
					}
				} catch (ex: KotlinReflectionInternalError) {
					null
				}
			} else {
				null
			}
		}
		return null
	}

}
