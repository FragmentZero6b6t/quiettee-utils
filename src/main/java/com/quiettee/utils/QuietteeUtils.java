package com.quiettee.utils;

import com.mojang.logging.LogUtils;
import com.quiettee.utils.modules.combat.BoatShot;
import com.quiettee.utils.modules.combat.Fusillade;
import com.quiettee.utils.modules.combat.Lance;
import com.quiettee.utils.modules.combat.MaceSmash;
import com.quiettee.utils.modules.movement.BoatPhase;
import com.quiettee.utils.modules.movement.FloatModule;
import com.quiettee.utils.modules.movement.GliderWalk;
import com.quiettee.utils.modules.movement.elytramotion.ElytraDive;
import com.quiettee.utils.modules.movement.elytramotion.ElytraFollow;
import com.quiettee.utils.modules.movement.elytramotion.ElytraOrbit;
import com.quiettee.utils.modules.player.AirMiner;
import com.quiettee.utils.modules.render.Flicker;
import com.quiettee.utils.modules.render.HighContrast;
import com.quiettee.utils.util.BlockBreaker;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

public class QuietteeUtils extends MeteorAddon {
    public static final String MOD_ID = "quiettee-utils";
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Quiettee Utils");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Quiettee Utils");

        MeteorClient.EVENT_BUS.subscribe(BlockBreaker.class);

        Modules modules = Modules.get();
        modules.add(new MaceSmash());
        modules.add(new Lance());
        modules.add(new Fusillade());
        modules.add(new BoatShot());
        modules.add(new AirMiner());
        modules.add(new FloatModule());
        modules.add(new GliderWalk());
        modules.add(new ElytraFollow());
        modules.add(new ElytraOrbit());
        modules.add(new ElytraDive());
        modules.add(new BoatPhase());
        modules.add(new Flicker());
        modules.add(new HighContrast());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.quiettee.utils";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("FragmentZero6b6t", "quiettee-utils");
    }

    public static Identifier identifier(String path) {
        return Identifier.of(MOD_ID, path);
    }
}
