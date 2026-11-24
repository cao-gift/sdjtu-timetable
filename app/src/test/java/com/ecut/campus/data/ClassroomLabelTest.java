package com.ecut.campus.data;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ClassroomLabelTest {
    @Test public void defaultKeepsCampusBuildingAndRoom() {
        String building = "无影山1号教学楼1102";
        assertEquals(building, ClassroomLabel.compact(building, Collections.emptyList()));
        assertEquals(building, ClassroomLabel.compact(building, null));
    }

    @Test public void stripsOnlyConfiguredBuildingAcrossMultipleLocations() {
        List<String> phrases = ClassroomLabel.parsePhrases("无影山1号教学楼\n长清校区二号楼");
        assertEquals("1102", ClassroomLabel.compact("无影山1号教学楼1102", phrases));
        assertEquals("A-203室", ClassroomLabel.compact("长清校区二号楼 A-203室", phrases));
        assertEquals("体育馆2", ClassroomLabel.compact("体育馆2", phrases));
    }

    @Test public void unmatchedPhrasesPreserveFullLocationIncludingWhitespace() {
        List<String> phrases = ClassroomLabel.parsePhrases("北教学区");
        String[] locations = {"无影山1号教学楼1102", "无影山线上虚拟教室", "教学楼1102、实验楼1103",
                "无影山1号教学楼\n1102", "  室外田径场  ", ""};
        for (String location : locations) {
            assertEquals(location, ClassroomLabel.compact(location, phrases));
        }
        assertEquals("", ClassroomLabel.compact(null, phrases));
    }

    @Test public void parsesMultiplePhrasesWithoutSplittingNamesContainingSpaces() {
        assertEquals(Arrays.asList("文体馆", "North Hall", "车队场", "南门"),
                ClassroomLabel.parsePhrases("　文体馆 ，North Hall,车队场\r\n南门、文体馆\n\n"));
        assertEquals(Collections.emptyList(), ClassroomLabel.parsePhrases("\r\n，、 ,　"));
        assertEquals(Collections.emptyList(), ClassroomLabel.parsePhrases(null));
    }

    @Test public void removesLiteralPhrasesAtAnyPositionIncludingRepeatedOccurrences() {
        List<String> phrases = ClassroomLabel.parsePhrases("[A+],$.*,西馆");
        assertEquals("分馆302 / 403", ClassroomLabel.compact("分馆[A+]302 / 西馆403[A+]$.*", phrases));
        assertEquals("", ClassroomLabel.compact("　西馆 $.*　", phrases));
    }

    @Test public void matchesLongestPhraseInOriginalTextOnly() {
        assertEquals("101", ClassroomLabel.compact("综合楼东翼101", ClassroomLabel.parsePhrases("综合楼,综合楼东翼")));
        assertEquals("东区", ClassroomLabel.compact("东旧区", ClassroomLabel.parsePhrases("旧,东区")));
    }

    @Test public void shortPhraseKeepsTheRestOfTheBuildingName() {
        List<String> phrases = ClassroomLabel.parsePhrases("无影山");
        assertEquals("1号教学楼1102", ClassroomLabel.compact("无影山1号教学楼1102", phrases));
    }
}
