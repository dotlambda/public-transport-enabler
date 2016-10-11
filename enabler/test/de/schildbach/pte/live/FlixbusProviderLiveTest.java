/*
 * Copyright 2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.pte.live;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.Date;
import java.util.List;

import org.junit.Test;

import de.schildbach.pte.FlixbusProvider;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.SuggestLocationsResult;

/**
 * @author Robert Schütz
 */
public class FlixbusProviderLiveTest extends AbstractProviderLiveTest {
    public FlixbusProviderLiveTest() {
        super(new FlixbusProvider(secretProperty("flixbus.api_authentication")));
    }

    @Test
    public void suggestLocationsKornwestheim() throws Exception {
        final SuggestLocationsResult stuttgartResult = suggestLocations("Stuttgart");
        final SuggestLocationsResult kornwestheimResult = suggestLocations("kornWEST");
        print(kornwestheimResult);
        final List<Location> stuttgartLocations = stuttgartResult.getLocations();
        final List<Location> kornwestheimLocations = kornwestheimResult.getLocations();
        assertEquals(kornwestheimLocations.size(), 1);
        // Kornwestheim should be one of the stations returned when searching for Stuttgart
        assertNotEquals(stuttgartLocations.indexOf(kornwestheimLocations.get(0)), -1);
    }

    @Test
    public void queryTripsAachenHeidelberg() throws Exception {
        final SuggestLocationsResult aachenResult = suggestLocations("aix-la-chapelle");
        final SuggestLocationsResult heidelbergResult = suggestLocations("heidelberg");
        final QueryTripsResult result = queryTrips(
            aachenResult.getLocations().get(0), null,
            heidelbergResult.getLocations().get(0),
            new Date(), true, null, null, null);
        print(result);
        for (int i = 0; i < result.trips.size(); i++) {
            final Trip trip = result.trips.get(i);
            assertFalse(trip.getFirstDepartureTime().before(new Date()));
        }
    }
}
