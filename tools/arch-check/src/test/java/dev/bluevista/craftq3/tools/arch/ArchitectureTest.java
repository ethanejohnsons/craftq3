package dev.bluevista.craftq3.tools.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Rules from the rewrite plan that the module graph alone cannot express. */
@AnalyzeClasses(
    packages = {"dev.bluevista.craftq3.engine", "dev.bluevista.craftq3.mc.hybrid"},
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule NO_MINECRAFT =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("net.minecraft..", "com.mojang..", "net.fabricmc..", "org.lwjgl..")
          .because("only mc:fabric may touch Minecraft, Fabric or GPU APIs")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule NO_HYBRID_IN_ENGINE =
      noClasses()
          .that()
          .resideInAPackage("dev.bluevista.craftq3.engine..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("dev.bluevista.craftq3.mc..")
          .because("pure Quake III never knows about the Minecraft bridge")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule NO_SYSTEM_PROPERTIES =
      noClasses()
          .that()
          .resideOutsideOfPackage("..config..")
          .should()
          .callMethod(System.class, "getProperty", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class, String.class)
          .orShould()
          .callMethod(Boolean.class, "getBoolean", String.class)
          .orShould()
          .callMethod(Integer.class, "getInteger", String.class)
          .because("configuration is read once by config loaders, never on hot paths")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule BOTLIB_SINGLE_THREADED =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("dev.bluevista.craftq3.engine.botlib..")
          .should()
          .notHaveModifier(JavaModifier.SYNCHRONIZED)
          .because("botlib is owned by the simulation thread")
          .allowEmptyShould(true);
}
