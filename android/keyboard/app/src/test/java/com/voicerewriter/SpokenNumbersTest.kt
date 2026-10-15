package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values are the desktop converter (speed-patch/owNumbers.js) outputs. */
class SpokenNumbersTest {
    @Test
    fun matchesDesktopConverter() {
        assertEquals("20 X only refers to the 5 hour limit.", SpokenNumbers.convert("Twenty X only refers to the five hour limit."))
        assertEquals("The T3 code sidebar is so much better.", SpokenNumbers.convert("The T three code sidebar is so much better."))
        assertEquals("3 people already signed up. 2 of them are on the free plan.", SpokenNumbers.convert("Three people already signed up. Two of them are on the free plan."))
        assertEquals("500 dollars a month is way too much.", SpokenNumbers.convert("Five hundred dollars a month is way too much."))
        assertEquals("It is like 20 or 30 requests a minute.", SpokenNumbers.convert("It is like twenty or thirty requests a minute."))
        assertEquals("One of them is broken but someone fixed the other one.", SpokenNumbers.convert("One of them is broken but someone fixed the other one."))
        assertEquals("Version 2.3 ships on the 21st.", SpokenNumbers.convert("Version two point three ships on the twenty first."))
        assertEquals("10 minutes. That is all it took.", SpokenNumbers.convert("Ten minutes. That is all it took."))
        assertEquals("I ran it a hundred times and got 95 percent.", SpokenNumbers.convert("I ran it a hundred times and got ninety five percent."))
        assertEquals("64 gigs of RAM on the M4 Max.", SpokenNumbers.convert("Sixty four gigs of RAM on the M four Max."))
        assertEquals("Meet at three thirty.", SpokenNumbers.convert("Meet at three thirty."))
        assertEquals("Back in twenty twenty six.", SpokenNumbers.convert("Back in twenty twenty six."))
        assertEquals("1 percent left. Testing 1, 2, 3.", SpokenNumbers.convert("One percent left. Testing one, two, three."))
        assertEquals("A 3 month plan with two-factor auth, a high five, zero data retention.", SpokenNumbers.convert("A three month plan with two-factor auth, a high five, zero data retention."))
        assertEquals("The 5-hour limit, 25 users, 42.", SpokenNumbers.convert("The five-hour limit, twenty-five users, forty-two."))
        assertEquals("One of them, one day, the one I sent, 1 hour.", SpokenNumbers.convert("One of them, one day, the one I sent, one hour."))
        assertEquals("Rank 3, one of us.", SpokenNumbers.convert("Rank 3, one of us."))
        assertEquals("1.5 x faster, 2.14.", SpokenNumbers.convert("One point five x faster, two point one four."))
        assertEquals("12,345 rows.", SpokenNumbers.convert("Twelve thousand three hundred and forty five rows."))
    }
}
