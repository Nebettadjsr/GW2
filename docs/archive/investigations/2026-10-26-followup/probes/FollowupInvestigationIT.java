package craft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import repo.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Investigation probes only: production source is unchanged. Never uses ArenaNet. */
class FollowupInvestigationIT {
    final Map<String,Object> report = new LinkedHashMap<>();
    final ObjectMapper json = new ObjectMapper();

    @Test void measureExistingBoundaries() throws Exception {
        report.put("applicationCommit", "d713eb46c03e3c5cfa3cdfc90ad5d5216b559752");
        report.put("processors", Runtime.getRuntime().availableProcessors());
        measureGraph();
        measureRootSelection();
        Path out = Path.of("target/followup/backend-probes.json");
        Files.createDirectories(out.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
    }

    void measureGraph() throws Exception {
        String schema = "followup_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect()) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                Flyway.configure().dataSource(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASS)
                        .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
                admin.setSchema(schema);
                int[] seeded=seedCatalog(admin);
                admin.createStatement().execute("INSERT INTO recipe_catalog_publication(complete) VALUES(TRUE)");
                var cache = new CraftingGraphCache(new RecipeRepository());
                var statements = new ArrayList<String>();
                try (Connection snapshot = connect()) {
                    snapshot.setSchema(schema);
                    snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    snapshot.setAutoCommit(false);
                    Connection observed = observe(snapshot, statements);
                    List<Double> millis = new ArrayList<>();
                    CraftingGraph first = null;
                    for (int run=0;run<6;run++) {
                        long start=System.nanoTime();
                        CraftingGraph graph=cache.loadForSnapshot(observed);
                        millis.add((System.nanoTime()-start)/1e6);
                        assertEquals(seeded[0], graph.getRecipes().size());
                        if(first==null) first=graph; else assertNotSame(first,graph);
                    }
                    int oldCount=first.getRecipes().getFirst().outputCount;
                    int firstId=first.getRecipes().getFirst().recipeId;
                    admin.createStatement().execute("UPDATE recipes SET output_item_count="+(oldCount+1)+" WHERE recipe_id="+firstId);
                    admin.createStatement().execute("UPDATE recipe_catalog_publication SET updated_at=clock_timestamp()");
                    assertEquals(oldCount,cache.loadForSnapshot(snapshot).getRecipes().getFirst().outputCount);
                    try(Connection newer=connect()) {
                        newer.setSchema(schema);
                        assertEquals(oldCount+1,cache.loadForSnapshot(newer).getRecipes().getFirst().outputCount);
                    }
                    report.put("snapshotGraph", Map.of("recipes",seeded[0],"ingredients",seeded[1],
                            "loadMillis",millis,"preparedStatementsAcrossSixLoads",statements.size(),
                            "sql",new LinkedHashSet<>(statements),"sameSnapshotReloadsDifferentObjects",true,
                            "oldSnapshotStayedOldAfterConcurrentCommit",true,"newSnapshotSawNewRecipe",true));
                    snapshot.rollback();
                }
            } finally { admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    Connection connect() throws SQLException { return DriverManager.getConnection(AppConfig.DB_URL,AppConfig.DB_USER,AppConfig.DB_PASS); }

    int[] seedCatalog(Connection con)throws Exception {
        Path path=Path.of(System.getProperty("gw2.investigation.catalog","../quality-reports/catalog.json"));
        byte[] bytes=Files.readAllBytes(path);
        report.put("catalogSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        report.put("graphCorpus","Existing public persisted catalog, copied read-only; SQL fixture on isolated database, not live account data");
        var corpus=json.readTree(bytes);
        var items=new HashSet<Integer>();
        for(var recipe:corpus.path("recipes")) {
            items.add(recipe.path("outputItemId").asInt());
            for(var ingredient:recipe.path("ingredients")) items.add(ingredient.path("itemId").asInt());
        }
        try(var item=con.prepareStatement("INSERT INTO items(item_id,name) VALUES(?,?)")) {
            for(int id:items){item.setInt(1,id);item.setString(2,corpus.path("itemNames").path(String.valueOf(id)).asText(null));item.addBatch();}
            item.executeBatch();
        }
        try(var recipe=con.prepareStatement("INSERT INTO recipes(recipe_id,output_item_id,output_item_count,min_rating,disciplines) VALUES(?,?,?,?,?)")) {
            for(var r:corpus.path("recipes")) {
                recipe.setInt(1,r.path("recipeId").asInt());recipe.setInt(2,r.path("outputItemId").asInt());
                recipe.setInt(3,r.path("outputCount").asInt());recipe.setInt(4,r.path("minRating").asInt());
                recipe.setArray(5,con.createArrayOf("text",r.path("disciplinesText").asText("").split(",\\s*")));recipe.addBatch();
            }
            recipe.executeBatch();
        }
        int count=0;
        try(var ingredient=con.prepareStatement("INSERT INTO recipe_ingredients(recipe_id,item_id,count) VALUES(?,?,?)")) {
            for(var r:corpus.path("recipes")) for(var i:r.path("ingredients")) {
                ingredient.setInt(1,r.path("recipeId").asInt());ingredient.setInt(2,i.path("itemId").asInt());
                ingredient.setInt(3,i.path("count").asInt());ingredient.addBatch();count++;
            }
            ingredient.executeBatch();
        }
        return new int[]{corpus.path("recipes").size(),count};
    }

    Connection observe(Connection target,List<String> statements) {
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")) statements.add((String)args[0]);
            try { return method.invoke(target,args); }
            catch(InvocationTargetException error) { throw error.getCause(); }
        });
    }

    void measureRootSelection() throws Exception {
        var recipes=new ArrayList<Recipe>();
        var prices=new HashMap<Integer,PriceQuote>();
        var stock=new HashMap<Integer,Integer>();
        for(int id=1;id<=400;id++){ stock.put(id,20);prices.put(id,new PriceQuote(5,8)); }
        for(int i=0;i<13000;i++) {
            int input=i<400?1+i:10000+(i-400)/2;
            recipes.add(new Recipe(i+1,10000+i,1+i%3,0,"Chef",List.of(new Ingredient(input,2))));
            prices.put(10000+i,new PriceQuote(20,30));
        }
        List<Recipe> roots=recipes.subList(12750,13000);
        Set<Integer> allowed=new HashSet<>();
        var pending=new ArrayDeque<>(roots);
        while(!pending.isEmpty()) {
            Recipe r=pending.removeFirst();
            if(!allowed.add(r.recipeId)) continue;
            for(Ingredient ing:r.ingredients) if(ing.itemId>=10000) pending.add(recipes.get(ing.itemId-10000));
        }
        var settings=new CraftingSettings(true,true,200,false,false,false);
        var roster=List.of(new CharacterCraftingProfile("Fixture Chef",Map.of("Chef",400)));
        Method existing=CraftingPlanner.class.getDeclaredMethod("evaluateOneRecipeNew",Recipe.class,PlanState.class,PlannerContext.class,RecipeSimulator.class);
        existing.setAccessible(true);
        var pairs=new ArrayList<Map<String,Object>>();
        for(int run=0;run<6;run++) {
            var outputs=new HashMap<Boolean,Map<Integer,CraftResult>>();
            var times=new HashMap<Boolean,Double>();
            for(boolean selected:run%2==0?List.of(false,true):List.of(true,false)) {
                long start=System.nanoTime();
                var planner=new CraftingPlanner();
                Map<Integer,CraftResult> result;
                if(!selected) result=planner.evaluateAllCoordinated(recipes,stock,Map.of(),Map.of(),roster,prices,settings,allowed);
                else {
                    var ctx=new PlannerContext(CraftingPlanner.buildRecipesByOutput(recipes),prices,settings,allowed,roster);
                    result=roots.parallelStream().collect(Collectors.toConcurrentMap(r->r.recipeId,r->{
                        try { return (CraftResult)existing.invoke(planner,r,new PlanState(stock),ctx,new RecipeSimulator()); }
                        catch(Exception error){throw new RuntimeException(error);}
                    }));
                }
                times.put(selected,(System.nanoTime()-start)/1e6);
                outputs.put(selected,result);
            }
            for(Recipe r:roots) assertEquals(json.valueToTree(outputs.get(false).get(r.recipeId)),json.valueToTree(outputs.get(true).get(r.recipeId)));
            pairs.add(Map.of("run",run,"allRootMillis",times.get(false),"selectedRootMillis",times.get(true),"visibleResultsEqual",true));
        }
        report.put("rootSelection",Map.of("allCatalogRoots",13000,"selectedRoots",250,"allowedRecipesIncludingNonRootDependencies",allowed.size(),
                "control", "existing public all-root planner", "experiment","existing private per-root evaluator invoked reflectively with the entire graph and unchanged permissions; no optimized production implementation",
                "pairs",pairs));
    }
}
