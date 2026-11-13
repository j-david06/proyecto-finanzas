package co.finanzas.adapters.in.rest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("api & demo & !worker")
public class DemoPanelController {
    @GetMapping({"/demo", "/demo/"})
    public String panel() { return "redirect:/demo/index.html"; }
}
