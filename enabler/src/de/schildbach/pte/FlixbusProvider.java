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

package de.schildbach.pte;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.Date;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import javax.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;

import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Fare;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.LineDestination;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
import de.schildbach.pte.dto.StationDepartures;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.Trip.Leg;
import de.schildbach.pte.util.ParserUtils;

import okhttp3.HttpUrl;

/**
 * @author Robert Schütz
 */
public class FlixbusProvider extends AbstractNetworkProvider {
    protected static final String API_BASE = "http://api.meinfernbus.de/mobile/v1/";

    private static final Line FLIXBUS_LINE = new Line(null, NetworkId.FLIXBUS.toString(), 
        Product.BUS, "FLIX", new Style(Style.parseColor("#73D700"), Style.WHITE));

    private static class Context implements QueryTripsContext {
        public boolean canQueryEarlier = false;
        public Location from;
        public Location to;
        public Date date;
      
        private Context(Location from, Location to, Date date) {
            this.from = from;
            this.to = to;
            this.date = date;
        }

        @Override
        public boolean canQueryLater() {
            return false;
        }

        @Override
        public boolean canQueryEarlier() {
            return this.canQueryEarlier;
        }
    }
    
    public FlixbusProvider(final String authentication) {
        super(NetworkId.FLIXBUS);

        httpClient.setHeader("X-API-Authentication", authentication);
        //TODO set language
    }

    private JSONArray getStationObjs() throws IOException, JSONException {
        final StringBuilder uri = new StringBuilder(API_BASE);
        uri.append("network.json");
        final CharSequence page = httpClient.get(HttpUrl.parse(uri.toString()), Charsets.UTF_8);
        final JSONObject head = new JSONObject(page.toString());
        return head.getJSONArray("stations");
    }

    private Location parseStation(final JSONObject stationObj) throws JSONException {
        final JSONObject coordObj = stationObj.getJSONObject("coordinates");
        return new Location(LocationType.STATION, stationObj.getString("id"),
            Point.fromDouble(coordObj.getDouble("latitude"), coordObj.getDouble("longitude")),
            stationObj.getString("full_address"), //TODO I'm not quite sure what the place argument is meant for
            stationObj.getString("name"));
    }

    private Location getStationById(int id) throws IOException, JSONException {
        final JSONArray stationObjs = getStationObjs();
        for (int i = 0; i < stationObjs.length(); i++) {
            final JSONObject stationObj = stationObjs.getJSONObject(i);
            if (stationObj.getInt("id") == id) {
                return parseStation(stationObj);
            }
        }
        return null;
    }

    @Override
    protected boolean hasCapability(Capability capability) {
        switch (capability) {
        case DEPARTURES:
            return false;
        case NEARBY_LOCATIONS:
            return false;
        case SUGGEST_LOCATIONS:
            return true;
        case TRIPS:
            return true;
        default:
            return false;
        }
    }

    @Override
    public NearbyLocationsResult queryNearbyLocations(EnumSet<LocationType> types, Location location,
        int maxDistance, int maxLocations) throws IOException {
        return null;
    }

    @Override
    public QueryDeparturesResult queryDepartures(String stationId, @Nullable Date time,
        int maxDepartures, boolean equivs) throws IOException {
        return null;
    }

    @Override
    public SuggestLocationsResult suggestLocations(final CharSequence constraint) throws IOException {
        final String search = constraint.toString().toLowerCase();
        final List<SuggestedLocation> locations = new ArrayList<>();
        try {
            final JSONArray stationObjs = getStationObjs();
            for (int i = 0; i < stationObjs.length(); i++) {
                final JSONObject stationObj = stationObjs.getJSONObject(i);
                if (stationObj.getString("aliases").toLowerCase().indexOf(search) != -1) {
                    locations.add(new SuggestedLocation(parseStation(stationObj)));
                }
            }
            return new SuggestLocationsResult(new ResultHeader(NetworkId.FLIXBUS, "meinfernbus", "v1", 0, stationObjs), locations);
        } catch (final JSONException x) {
            throw new RuntimeException("cannot parse JSON on network.json", x);
        }
    }

    // via not supported.
    // dep not supported.
    // walkSpeed not supported.
    // accessibility not supported.
    // options not supported.
    @Override
    public QueryTripsResult queryTrips(final Location from, final @Nullable Location via, final Location to, Date date,
            boolean dep, final @Nullable Set<Product> products, final @Nullable Optimize optimize,
            final @Nullable WalkSpeed walkSpeed, final @Nullable Accessibility accessibility,
            @Nullable Set<Option> options) throws IOException {
        final StringBuilder uri = new StringBuilder(API_BASE);
        uri.append("trip/search.json?adult=1&back=0&bikes=0&children=0&currency=EUR&return_date=");

        // departure date
        final Calendar c = new GregorianCalendar(TimeZone.getTimeZone("Europe/Berlin"));
        c.setTime(date);
        final int year = c.get(Calendar.YEAR);
        final int month = c.get(Calendar.MONTH) + 1;
        final int day = c.get(Calendar.DAY_OF_MONTH);
        uri.append("&departure_date=");
        uri.append(ParserUtils.urlEncode(String.format("%02d.%02d.%04d", day, month, year)));
        
        // by appending "&search_by=cities", you could retrieve trips by citiy ID instead of station ID
        uri.append("&from=").append(from.id);
        uri.append("&to=").append(to.id);

        final CharSequence page = httpClient.get(HttpUrl.parse(uri.toString()), Charsets.UTF_8);

        try {
            final JSONObject head = new JSONObject(page.toString());
            final JSONArray tripObjs = head.getJSONArray("trips");

            if (tripObjs.length() == 0) {
                //TODO no trips found 
            } else if (tripObjs.length() > 1) {
                //TODO error: search did not return exactly one station to exactly one other station
            }
            
            final List<Trip> trips = new ArrayList<>();
            final Context context = new Context(from, to, date);
            final JSONObject tripObj = tripObjs.getJSONObject(0);
            // maybe we should check if from.id == tripObj.getJSONObject("from").getString("id")
            // and the same for to.id

            final JSONArray itemObjs = tripObj.getJSONArray("items");
            for (int i = 0; i < itemObjs.length(); i++) {
                final JSONObject itemObj = itemObjs.getJSONObject(i);

                // Java uses millisecond timestamp whereas this is in seconds, so *1000
                final Date departureTime =
                    new Date(itemObj.getJSONObject("departure").getInt("timestamp") * 1000);
                // ignore the item if its departure is before the time searched for
                if (departureTime.before(date)) {
                    // if there is a trip before the given time but on the same day,
                    // a subsequent queryMoreTrips() will return it
                    context.canQueryEarlier = true;
                    continue;
                }

                final Stop firstStop = new Stop(from, true, departureTime, null, null, null);
                final Stop lastStop = new Stop(to, false,
                    new Date(itemObj.getJSONObject("arrival").getInt("timestamp") * 1000),
                    null, null, null);

                List<Leg> legs = new ArrayList<Leg>();
                final List<Stop> departures = new ArrayList<Stop>();
                final List<Stop> arrivals = new ArrayList<Stop>();
                departures.add(firstStop);

                if (itemObj.getString("type") == "interconnection") { // trip has mutliple legs
                    final JSONArray transferObjs = itemObj.getJSONArray("interconnection_transfers");
                    for (int j = 0; j < transferObjs.length(); j++) {
                        // parse transfer and add arrival, departure to lists
                        final JSONObject transferObj = transferObjs.getJSONObject(j);
                        final Location location = getStationById(transferObj.getInt("station_id"));
                         
                        arrivals.add(new Stop(location, false,
                            new Date(transferObj.getJSONObject("arrival").getInt("timestamp") * 1000),
                            null, null, null));
                        departures.add(new Stop(location, true,
                            new Date(transferObj.getJSONObject("departure").getInt("timestamp") * 1000),
                            null, null, null));
                    }
                } else if (itemObj.getString("type") != "direct") {
                    //TODO error: unknown type
                }

                arrivals.add(lastStop);
                for (int j = 0; j < departures.size(); j++) {
                    legs.add(new Trip.Public(FLIXBUS_LINE, null,
                        departures.get(j), arrivals.get(j), null, null, null));
                }
                
                //TODO fares
                trips.add(new Trip(itemObj.getString("uid"), from, to, legs, null, null, legs.size() - 1));
            }
            return new QueryTripsResult(null, uri.toString(), from, null, to, context, trips);
        } catch (final JSONException x) {
            throw new RuntimeException("cannot parse JSON on " + uri, x);
        }
    }

    @Override
    public QueryTripsResult queryMoreTrips(QueryTripsContext context, boolean later) throws IOException {
        if (later) {
            //TODO error: later not supported
            return null;
        } else {
            final Context ctx = (Context) context;
            final Calendar c = new GregorianCalendar(TimeZone.getTimeZone("Europe/Berlin"));
            c.setTime(ctx.date);
            // query all trips from the same day, but from 00:00 on
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            return queryTrips(ctx.from, null, ctx.to, c.getTime(), false, null, null, null, null, null);
        }
    }
}
