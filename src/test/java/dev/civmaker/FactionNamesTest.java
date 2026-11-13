package dev.civmaker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FactionNamesTest {
    private final FactionNames names = new FactionNames(List.of(), List.of());

    @ParameterizedTest
    @ValueSource(strings = {
            "Nazis", "NAZI Empire", "N4Z1", "N_4_Z_1", "N A Z I", "N--A--Z--I",
            "nnn444zzz111", "XxNeoNazisxX", "Neo_Nazism", "H17L3R", "H1T13R",
            "Heil Hitler", "S13g H31l", "Third Reich", "W4FF3N_55", "Gestapo", "Schutzstaffel",
            "Ku Klux Klan", "Ku-Klux", "K K K", "KKKArmy", "Wh173 P0w3r", "White Supremacy",
            "Aryan Brotherhood", "4ry4n N4710n5", "Atomwaffen", "Blood and Honour",
            "Al Qaeda", "Al_Q43d4", "Al Qaida", "ISIS", "1S1S", "I S I S", "ISISClan",
            "ISIL Empire", "Daesh", "Boko Haram", "Islamic State", "XxISISxX", "xX_ISIL_Xx",
            "superwhite powerarmy", "T4l1b4n", "NSDAP", "N5D4P", "N S D A P", "Nationalsozialismus", "Sturmabteilung"
    })
    void rejectsExtremistReferencesAndObfuscatedVariants(String name) {
        assertEquals(FactionNames.CONTENT_ERROR, names.error(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fuck", "FUCKERS", "F U C K", "F_U_C_K", "fuuuccckkk", "fvck", "phuck", "fuk",
            "5H17", "sh_it", "ShitTown", "shy7", "b17ch", "Biatch", "4ssh0l3", "a55h013",
            "Bastards", "Motherfucker", "Wankers", "d4mn", "Ass", "4ss", "Sex Clan", "T17s",
            "n1gg3r", "n1gg4", "f4gg07", "K1K3", "R4gh34d", "T0w3lh34d",
            "f uckland", "fu ckland", "Xx4ssxX", "P155", "Cr4p", "Arse", "Prick", "Bugger", "Douchebag"
    })
    void rejectsProfanitySlursAndLeetspeak(String name) {
        assertEquals(FactionNames.CONTENT_ERROR, names.error(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ashford", "Meadow Republic", "Night Watch", "Iron_Legion", "Blue-Foxes", "Legion 7",
            "Classic Legion", "Grasslands", "Brasshold", "Assassins", "Assassin Guild", "Classical Union",
            "Assembly", "Embassy", "Compass Keep", "Scunthorpe", "Scun7horpe", "Penistone", "Cockburn",
            "Cockpit Crew", "Peacock Kingdom", "Dickens", "Essex", "Sussex", "Wessex", "Titan Forge",
            "Titanium", "Constitution", "Shiitake", "Shitake", "Raccoon Lodge", "Grapevine", "Butterfly",
            "Mongol Horde", "Pakistani Guild", "Montenegro", "This Is Home", "ThisIsHome", "Isisford",
            "Hardcore Builders", "World Domination", "Xx_Knights_xX", "Empire 88", "Aryan Empire",
            "Christian Guard", "Muslim League", "Jewish Guild", "Pride Alliance", "Gay Gamers",
            "NightWatch", "Market Watch", "West Watch", "Great Watch", "Fukushima",
            "Arsenal", "Scrap Guild", "ScrapRaiders", "Prickly Pear", "Debugger Guild"
    })
    void acceptsOrdinaryNamesAndAvoidsSubstringFalsePositives(String name) {
        assertNull(names.error(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "A", "-Ashford", "_Ashford", " Ashford", "Ashford!", "f@ck", "nаzi", "nαzi",
            "ＮＡＺＩ", "n\u200bazi", "n\u200dazi", "na\u0301zi", "na\u202ezi", "§cNazis", "Ashford\n"
    })
    void rejectsInvalidCharactersUnicodeLookalikesAndInvisibleCharacters(String name) {
        assertEquals(FactionNames.FORMAT_ERROR, names.error(name), name);
    }

    @Test void enforcesLengthAndRejectsUnboundedInputBeforeModeration() {
        assertNull(names.error("Ab"));
        assertNull(names.error("A".repeat(24)));
        assertEquals(FactionNames.FORMAT_ERROR, names.error("A".repeat(25)));
        assertEquals(FactionNames.FORMAT_ERROR, names.error("a".repeat(100_000)));
        assertEquals(FactionNames.FORMAT_ERROR, names.error(null));
    }

    @Test void exceptionsNeverWhitelistOtherOffensivePartsOfTheName() {
        for (String name : List.of("Classic Nazis", "ScunthorpeHitler", "Grass Fuckers", "AssassinWhitePower",
                "PenistoneNazi", "N4ziClassic", "PakistaniHitler")) {
            assertEquals(FactionNames.CONTENT_ERROR, names.error(name), name);
        }
    }

    @Test void serverSpecificTermsUseTheSameObfuscationChecksAndExceptionsAreScoped() {
        FactionNames custom = new FactionNames(List.of("Creeper"), List.of("Creeperton"));
        assertEquals(FactionNames.CONTENT_ERROR, custom.error("Cr33p3r Guild"));
        assertEquals(FactionNames.CONTENT_ERROR, custom.error("C r e e p e r"));
        assertNull(custom.error("Creeperton"));
        assertEquals(FactionNames.CONTENT_ERROR, custom.error("CreepertonNazi"));
        assertEquals(FactionNames.CONTENT_ERROR, custom.error("Creeperton Creeper"));
    }
}
