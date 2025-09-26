package uk.ac.ebi.zooma2;

import io.javalin.Javalin;
import uk.ac.ebi.zooma2.repo.MappingTablesRepo;

public class Zooma2App {
   public static void main(String[] args) {

        MappingTablesRepo tablesRepo = new MappingTablesRepo();

        var app = Javalin.create(/*config*/)
            .get("/", ctx -> ctx.result("Hello World"))
            .start(7070);
    }
}