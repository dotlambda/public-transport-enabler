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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.net.SocketTimeoutException;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Ignore;
import org.junit.Test;

import com.google.common.collect.ComparisonChain;

import de.schildbach.pte.NetworkProvider.Accessibility;
import de.schildbach.pte.NetworkProvider.WalkSpeed;
import de.schildbach.pte.FlixbusProvider;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.LineDestination;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.StationDepartures;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.util.Iso8601Format;

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
        final List<Location> stuttgartLocations = stuttgartResult.getLocations();
        final List<Location> kornwestheimLocations = kornwestheimResult.getLocations();
        assertEquals(kornwestheimLocations.size(), 1);
        // Kornwestheim should be one of the stations returned when searching for Stuttgart
        assertNotEquals(stuttgartLocations.indexOf(kornwestheimLocations.get(0)), -1);
    }
}
