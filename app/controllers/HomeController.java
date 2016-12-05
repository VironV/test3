package controllers;

import play.mvc.*;
import views.html.*;
import views.html.helper.*;

public class HomeController extends Controller {


    public Result index() {
        return ok(index.render("friend"));
    }

}
