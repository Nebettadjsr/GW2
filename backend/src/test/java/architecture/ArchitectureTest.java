package architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Executable form of the boundary rules in {@code AGENTS.md} and {@code docs/ARCHITECTURE.md}.
 *
 * <p>Every rule here is strict and holds today. Do not weaken a rule or add an exception to make a change
 * pass; fix the dependency or raise the boundary question in the issue.
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

  // ---- web and repositories (owner decision: controllers may read through repositories) --------

  /**
   * The web layer except the Spring entry point. {@code Gw2ApiApplication} is the composition root: it
   * wires infrastructure (connection suppliers, repositories) into application services, so it is the one
   * place in {@code web} that may touch persistence plumbing.
   */
  private static final DescribedPredicate<JavaClass> WEB_LAYER =
      DescribedPredicate.describe(
          "the web layer except the Spring entry point Gw2ApiApplication",
          (JavaClass c) ->
              (c.getPackageName().equals("web") || c.getPackageName().startsWith("web."))
                  && !c.getName().startsWith("web.Gw2ApiApplication"));

  /**
   * JDBC and connection plumbing (connections, statements, result sets, drivers, data sources, the
   * connection factory {@code repo.Db}). Plain data carriers stay allowed in the web layer: the SQL
   * exceptions used for error mapping and {@code Timestamp}/{@code Date}/{@code Time} values that a
   * repository returns.
   */
  private static final DescribedPredicate<JavaClass> CONNECTION_PLUMBING =
      DescribedPredicate.describe(
          "JDBC or connection plumbing (SQL exceptions and Timestamp/Date/Time values are allowed)",
          (JavaClass t) -> {
            String name = t.getName();
            boolean jdbcDataCarrier =
                name.endsWith("Exception")
                    || name.equals("java.sql.Timestamp")
                    || name.equals("java.sql.Date")
                    || name.equals("java.sql.Time");
            return (name.startsWith("java.sql.") && !jdbcDataCarrier)
                || name.startsWith("javax.sql.")
                || name.startsWith("org.postgresql.")
                || name.equals("repo.Db");
          });

  /** Repository methods that change data or schema state, recognised by their verb. */
  private static final java.util.regex.Pattern MUTATING_REPOSITORY_METHOD =
      java.util.regex.Pattern.compile(
          "(insert|update|delete|upsert|save|publish|replace|write|store|persist|remove|clear|purge|reset"
              + "|advance|register|record|migrate)([A-Z_].*)?");

  @ArchTest
  static final ArchRule webDoesNotOpenConnectionsOrRunSql =
      noClasses()
          .that(WEB_LAYER)
          .should()
          .dependOnClassesThat(CONNECTION_PLUMBING)
          .because(
              "controllers may read through repositories but never hold a connection or run SQL;"
                  + " a repository method that connects by itself covers the need");

  @ArchTest
  static final ArchRule webDoesNotWriteThroughRepositories =
      noClasses()
          .that(WEB_LAYER)
          .should()
          .callMethodWhere(
              DescribedPredicate.describe(
                  "a repository method that changes data (insert, update, delete, upsert, save, publish, ...)",
                  call ->
                      call.getTargetOwner().getPackageName().startsWith("repo")
                          && MUTATING_REPOSITORY_METHOD.matcher(call.getName()).matches()))
          .because("writes and publication belong in application services that own the transaction");

  // ---- strict rules, continued ----------------------------------------------------------------

  @ArchTest
  static final ArchRule topLevelPackagesAreFreeOfCycles =
      slices()
          .matching("(*)..")
          .should()
          .beFreeOfCycles()
          .because("cyclic package dependencies make boundaries meaningless");

  /**
   * The direction every dependency between the packages must follow, lowest layer first. A package may
   * use the layers below it and never one above it.
   */
  @ArchTest
  static final ArchRule packagesDependOnlyDownwards =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Foundation")
          .definedBy("util..", "model..", "domain..", "tradingpost..", "luck..", "craft..")
          .layer("Parser")
          .definedBy("parser..")
          .layer("Api")
          .definedBy("api..")
          .layer("Repo")
          .definedBy("repo..")
          .layer("Infra")
          .definedBy("infra..")
          .layer("Sync")
          .definedBy("sync..")
          .layer("Application")
          .definedBy("application..")
          .layer("Web")
          .definedBy("web..")
          .whereLayer("Web")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("Web")
          .whereLayer("Sync")
          .mayOnlyBeAccessedByLayers("Application", "Web")
          .whereLayer("Infra")
          .mayOnlyBeAccessedByLayers("Sync", "Application", "Web")
          .whereLayer("Repo")
          .mayOnlyBeAccessedByLayers("Infra", "Sync", "Application", "Web")
          .whereLayer("Api")
          .mayOnlyBeAccessedByLayers("Repo", "Infra", "Sync", "Application", "Web")
          .whereLayer("Parser")
          .mayOnlyBeAccessedByLayers("Api", "Repo", "Infra", "Sync", "Application", "Web")
          .because("a lower layer must not know the layers above it");

  /**
   * The application classes that own a database snapshot or a transaction and therefore hold a
   * {@code java.sql.Connection} (owner decision, issue #419). Every other application class must not
   * touch JDBC. Add a class here only with a reason in the pull request.
   */
  private static final String SNAPSHOT_AND_TRANSACTION_CLASSES =
      "application\\.(AccountDataDeletionService|AccountOverviewService|CalculationInputVersions"
          + "|CharacterRosterService|CraftingDiscoveryService|CraftingProfitService"
          + "|OwnerCatalogSummaryService|ProfitInputSession|ScopedAccountRefreshCoordinator"
          + "|ScopedSourceGate)(\\$.*)?";

  @ArchTest
  static final ArchRule onlySnapshotAndTransactionClassesTouchJdbc =
      noClasses()
          .that()
          .resideInAPackage("application..")
          .and()
          .haveNameNotMatching(SNAPSHOT_AND_TRANSACTION_CLASSES)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("java.sql..", "javax.sql..", "org.postgresql..")
          .because(
              "application services see the database only through repositories and"
                  + " DataStoreException; the listed snapshot and transaction classes may hold a"
                  + " connection");

  @ArchTest
  static final ArchRule applicationNeverRunsSql =
      noClasses()
          .that()
          .resideInAPackage("application..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.sql.Statement")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.sql.PreparedStatement")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.sql.CallableStatement")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.sql.ResultSet")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.sql.DriverManager")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("javax.sql.DataSource")
          .because("SQL belongs in repositories, even for the snapshot classes");
}
