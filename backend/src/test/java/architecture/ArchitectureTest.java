package architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Executable form of the boundary rules in {@code AGENTS.md} and {@code docs/ARCHITECTURE.md}.
 *
 * <p>Rules marked "strict" hold today and must keep holding. Rules wrapped in {@code freeze(...)}
 * describe the target state; violations that already existed are recorded in
 * {@code src/test/resources/archunit_store} and may only shrink. A new violation fails the build.
 * Do not weaken a rule or extend the store to make a change pass; fix the dependency or raise the
 * boundary question in the issue.
 */
@AnalyzeClasses(
    packages = {
      "api", "application", "craft", "domain", "infra", "luck", "model", "parser", "repo", "sync",
      "tradingpost", "util", "web"
    },
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /** Packages that hold domain rules and value types. */
  private static final String[] CORE = {"craft..", "domain..", "model..", "tradingpost..", "luck.."};

  // ---- strict rules (hold today) -------------------------------------------------------------

  @ArchTest
  static final ArchRule coreDoesNotDependOnAdaptersOrFrameworks =
      noClasses()
          .that()
          .resideInAnyPackage(CORE)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "web..",
              "application..",
              "repo..",
              "infra..",
              "api..",
              "sync..",
              "parser..",
              "org.springframework..",
              "jakarta..",
              "java.sql..",
              "javax.sql..",
              "org.postgresql..",
              "com.fasterxml.jackson..",
              "java.net.http..")
          .because("domain logic must not depend on HTTP, JSON, JDBC, Spring or adapters");

  @ArchTest
  static final ArchRule springIsConfinedToWeb =
      noClasses()
          .that()
          .resideOutsideOfPackage("web..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..")
          .because("Spring belongs to the web entry point and its transport code");

  @ArchTest
  static final ArchRule nothingBelowWebDependsOnWeb =
      noClasses()
          .that()
          .resideOutsideOfPackage("web..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("web..")
          .because("web is the outermost layer");

  // ---- frozen rules (target state, existing violations recorded) ------------------------------

  @ArchTest
  static final ArchRule webDoesNotUseJdbcDirectly =
      freeze(
          noClasses()
              .that()
              .resideInAPackage("web..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage("java.sql..", "javax.sql..", "org.postgresql..")
              .because("controllers delegate to application services; SQL belongs in repo"));

  @ArchTest
  static final ArchRule webDoesNotReachIntoRepositories =
      freeze(
          noClasses()
              .that()
              .resideInAPackage("web..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage("repo..")
              .because("controllers use application services, not repositories"));

  @ArchTest
  static final ArchRule applicationDoesNotUseJdbcDirectly =
      freeze(
          noClasses()
              .that()
              .resideInAPackage("application..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage("java.sql..", "javax.sql..", "org.postgresql..")
              .because("external systems belong behind adapters"));

  @ArchTest
  static final ArchRule topLevelPackagesAreFreeOfCycles =
      freeze(
          slices()
              .matching("(*)..")
              .should()
              .beFreeOfCycles()
              .because("cyclic package dependencies make boundaries meaningless"));
}
