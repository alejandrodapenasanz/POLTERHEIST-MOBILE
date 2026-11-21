package org.polterheist.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class ByteRangeTest {
    @Test public void fullFileAndEmptyFile() {
        assertEquals(100,ByteRange.parse(null,100).length());
        assertFalse(ByteRange.parse(null,100).partial);
        assertEquals(0,ByteRange.parse(null,0).length());
    }
    @Test public void boundedAndOpenRanges() {
        ByteRange r=ByteRange.parse("bytes=10-19",100);
        assertEquals(10,r.start);assertEquals(19,r.end);assertEquals(10,r.length());assertTrue(r.partial);
        assertEquals(90,ByteRange.parse("bytes=10-",100).length());
        assertEquals(90,ByteRange.parse("bytes=10-900",100).length());
    }
    @Test public void suffixRanges() {
        assertEquals(95,ByteRange.parse("bytes=-5",100).start);
        assertEquals(100,ByteRange.parse("bytes=-500",100).length());
    }
    @Test public void rejectsMalformedAndUnsatisfiableRanges() {
        for(String range:new String[]{"bytes=", "bytes=-", "bytes=-0", "bytes=100-", "bytes=9-2", "bytes=0-2,4-6", "items=1-2", "bytes=9999999999999999999999999-"}) {
            assertThrows(range,IllegalArgumentException.class,()->ByteRange.parse(range,100));
        }
        assertThrows(IllegalArgumentException.class,()->ByteRange.parse("bytes=0-0",0));
    }
}
