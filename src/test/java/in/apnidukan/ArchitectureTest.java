package in.apnidukan;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * House rules for how the code is organised, checked on every build. If a change breaks one, the test says
 * which class and which rule. Every package directly under in.apnidukan is a module (auth, files, and later shops,
 * orders, ...) and is covered automatically; common is shared by all; config is app-level wiring.
 */
@AnalyzeClasses(packages = "in.apnidukan", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** common is the foundation: it must not know about any module, present or future. */
    @ArchTest
    static final ArchRule commonDependsOnNoModule = noClasses()
            .that().resideInAPackage("in.apnidukan.common..")
            .should().dependOnClassesThat(resideInAPackage("in.apnidukan..")
                    .and(not(resideInAPackage("in.apnidukan.common.."))));

    /** Modules don't reach into each other; shared things go through common. Covers new modules automatically. */
    @ArchTest
    static final ArchRule modulesAreIndependent = slices()
            .matching("in.apnidukan.(*)..").namingSlices("$1")
            .should().notDependOnEachOther()
            .ignoreDependency(alwaysTrue(), resideInAnyPackage("in.apnidukan.common.."))
            .ignoreDependency(resideInAnyPackage("in.apnidukan.config.."), alwaysTrue());

    /** Controllers only talk to services, never straight to the database. */
    @ArchTest
    static final ArchRule controllersDontUseRepositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().resideInAPackage("..repo..");

    /** Repositories are used only by services (where transactions and checks live). */
    @ArchTest
    static final ArchRule repositoriesOnlyFromServices = classes()
            .that().resideInAPackage("..repo..")
            .should().onlyBeAccessed().byAnyPackage("..service..", "..repo..");

    /** Database entities live in each module's domain package. */
    @ArchTest
    static final ArchRule entitiesInDomain = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..domain..");

    /** Only security and sign-in may create or read sign-in tokens. */
    @ArchTest
    static final ArchRule tokensOnlyInSecurityAndSignIn = classes()
            .that().haveNameMatching("in\\.apnidukan\\.common\\.security\\.JwtService(\\$.*)?")
            .should().onlyBeAccessed().byAnyPackage("in.apnidukan.common.security..", "in.apnidukan.auth.service..");

    /** Dependencies come through constructors (easy to test, can't be forgotten), not field injection. */
    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should().beAnnotatedWith(Autowired.class)
            .orShould().beAnnotatedWith(Value.class)
            .orShould().beAnnotatedWith("jakarta.inject.Inject")
            .orShould().beAnnotatedWith("jakarta.annotation.Resource");

    @ArchTest
    static final ArchRule noSetterInjection = noMethods()
            .should().beAnnotatedWith(Autowired.class)
            .orShould().beAnnotatedWith("jakarta.inject.Inject");

    /** Logging goes through the logger (SLF4J), never System.out, so nothing sensitive lands in stray output. */
    @ArchTest
    static final ArchRule noSystemOut = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    /** Throw a specific exception (ApiException, IllegalStateException), not a bare Exception/RuntimeException. */
    @ArchTest
    static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
}
