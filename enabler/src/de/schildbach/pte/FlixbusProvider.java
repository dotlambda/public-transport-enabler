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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    @SuppressWarnings("serial")
    private static class Context implements QueryTripsContext {
        private boolean canQueryLater = true;
        private boolean canQueryEarlier = true;
        private Date lastDeparture = null;
        private Date firstArrival = null;
        public Location from;
        public Location via;
        public Location to;
        public Set<Product> products;

        private Context() {
        }

        @Override
        public boolean canQueryLater() {
            return this.canQueryLater;
        }

        @Override
        public boolean canQueryEarlier() {
            return this.canQueryEarlier;
        }

        public void departure(Date departure) {
            if (this.lastDeparture == null || this.lastDeparture.compareTo(departure) < 0) {
                this.lastDeparture = departure;
            }
        }

        public void arrival(Date arrival) {
            if (this.firstArrival == null || this.firstArrival.compareTo(arrival) > 0) {
                this.firstArrival = arrival;
            }
        }

        public Date getLastDeparture() {
            return this.lastDeparture;
        }

        public Date getFirstArrival() {
            return this.firstArrival;
        }

        public void disableEarlier() {
            this.canQueryEarlier = false;
        }

        public void disableLater() {
            this.canQueryLater = false;
        }
    }

    protected static final String API_BASE = "http://api.meinfernbus.de/mobile/v1/";

    protected static final Map<String, Style> STYLES = new HashMap<String, Style>();

    static {
        // Stadtbahn Köln-Bonn
        STYLES.put("B156", new Style(Style.parseColor("#4B69EC"), Style.WHITE));
    }

    public FlixbusProvider() {
        super(NetworkId.FLIXBUS);

        // setStyles(STYLES);
    }

    @Override
    protected boolean hasCapability(Capability capability) {
        switch (capability) {
        case DEPARTURES:
            return false;
        case NEARBY_LOCATIONS:
            return false;
        case SUGGEST_LOCATIONS:
            return false;
        case TRIPS:
            return true;
        default:
            return false;
        }
    }

    // only stations supported
    @Override
    public NearbyLocationsResult queryNearbyLocations(EnumSet<LocationType> types /* only STATION supported */,
            Location location, int maxDistance, int maxLocations) throws IOException {
        // g=p means group by product; not used here
        final StringBuilder uri = new StringBuilder(API_BASE);
        uri.append("?eID=tx_vrsinfo_ass2_timetable");
        if (location.hasLocation()) {
            uri.append("&r=")
                    .append(String.format(Locale.ENGLISH, "%.6f,%.6f", location.lat / 1E6, location.lon / 1E6));
        } else if (location.type == LocationType.STATION && location.hasId()) {
            uri.append("&i=").append(ParserUtils.urlEncode(location.id));
        } else {
            throw new IllegalArgumentException("at least one of stationId or lat/lon must be given");
        }
        // c=1 limits the departures at each stop to 1 - actually we don't need any at this point
        uri.append("&c=1");
        if (maxLocations > 0) {
            // s=number of stops
            uri.append("&s=").append(Math.min(16, maxLocations)); // artificial server limit
        }

        final CharSequence page = httpClient.get(HttpUrl.parse(uri.toString()), Charsets.UTF_8);

        try {
            final List<Location> locations = new ArrayList<Location>();
            final JSONObject head = new JSONObject(page.toString());
            final String error = Strings.emptyToNull(head.optString("error", "").trim());
            if (error != null) {
                if (error.equals("Leere Koordinate.") || error.equals("Leere ASS-ID und leere Koordinate"))
                    return new NearbyLocationsResult(new ResultHeader(NetworkId.VRS, SERVER_PRODUCT, null, 0, null),
                            locations);
                else if (error.equals("ASS2-Server lieferte leere Antwort."))
                    return new NearbyLocationsResult(new ResultHeader(NetworkId.VRS, SERVER_PRODUCT),
                            NearbyLocationsResult.Status.SERVICE_DOWN);
                else
                    throw new IllegalStateException("unknown error: " + error);
            }
            final JSONArray timetable = head.getJSONArray("timetable");
            long serverTime = 0;
            for (int i = 0; i < timetable.length(); i++) {
                final JSONObject entry = timetable.getJSONObject(i);
                final JSONObject stop = entry.getJSONObject("stop");
                final Location loc = parseLocationAndPosition(stop).location;
                int distance = stop.getInt("distance");
                if (maxDistance > 0 && distance > maxDistance) {
                    break; // we rely on the server side sorting by distance
                }
                if (types.contains(loc.type) || types.contains(LocationType.ANY)) {
                    locations.add(loc);
                }
                serverTime = parseDateTime(timetable.getJSONObject(i).getString("generated")).getTime();
            }
            final ResultHeader header = new ResultHeader(NetworkId.VRS, SERVER_PRODUCT, null, serverTime, null);
            return new NearbyLocationsResult(header, locations);
        } catch (final JSONException x) {
            throw new RuntimeException("cannot parse: '" + page + "' on " + uri, x);
        } catch (final ParseException e) {
            throw new RuntimeException("cannot parse: '" + page + "' on " + uri, e);
        }
    }

    // VRS does not show LongDistanceTrains departures. Parameter p for product
    // filter is supported, but LongDistanceTrains filter seems to be ignored.
    // TODO equivs not supported; JSON result would support multiple timetables
    @Override
    public QueryDeparturesResult queryDepartures(final String stationId, @Nullable Date time, int maxDepartures,
            boolean equivs) throws IOException {
        checkNotNull(Strings.emptyToNull(stationId));

        // g=p means group by product; not used here
        // d=minutes overwrites c=count and returns departures for the next d minutes
        final StringBuilder uri = new StringBuilder(API_BASE);
        uri.append("?eID=tx_vrsinfo_ass2_timetable&i=").append(ParserUtils.urlEncode(stationId));
        uri.append("&c=").append(maxDepartures);
        if (time != null) {
            uri.append("&t=");
            appendDate(uri, time);
        }
        final CharSequence page = httpClient.get(HttpUrl.parse(uri.toString()), Charsets.UTF_8);

        try {
            final JSONObject head = new JSONObject(page.toString());
            final String error = Strings.emptyToNull(head.optString("error", "").trim());
            if (error != null) {
                if (error.equals("ASS2-Server lieferte leere Antwort."))
                    return new QueryDeparturesResult(new ResultHeader(NetworkId.VRS, SERVER_PRODUCT),
                            QueryDeparturesResult.Status.SERVICE_DOWN);
                else if (error.equals("Leere ASS-ID und leere Koordinate"))
                    return new QueryDeparturesResult(new ResultHeader(NetworkId.VRS, SERVER_PRODUCT),
                            QueryDeparturesResult.Status.INVALID_STATION);
                else if (error.equals("Keine Abfahrten gefunden."))
                    return new QueryDeparturesResult(new ResultHeader(NetworkId.VRS, SERVER_PRODUCT),
                            QueryDeparturesResult.Status.INVALID_STATION);
                else
                    throw new IllegalStateException("unknown error: " + error);
            }
            final JSONArray timetable = head.getJSONArray("timetable");
            final ResultHeader header = new ResultHeader(NetworkId.VRS, SERVER_PRODUCT);
            final QueryDeparturesResult result = new QueryDeparturesResult(header);
            // for all stations
            if (timetable.length() == 0) {
                return new QueryDeparturesResult(header, QueryDeparturesResult.Status.INVALID_STATION);
            }
            for (int iStation = 0; iStation < timetable.length(); iStation++) {
                final List<Departure> departures = new ArrayList<Departure>();
                final JSONObject station = timetable.getJSONObject(iStation);
                final Location location = parseLocationAndPosition(station.getJSONObject("stop")).location;
                final JSONArray events = station.getJSONArray("events");
                final List<LineDestination> lines = new ArrayList<LineDestination>();
                // for all departures
                for (int iEvent = 0; iEvent < events.length(); iEvent++) {
                    final JSONObject event = events.getJSONObject(iEvent);
                    Date plannedTime = null;
                    Date predictedTime = null;
                    if (event.has("departureScheduled")) {
                        plannedTime = parseDateTime(event.getString("departureScheduled"));
                        predictedTime = parseDateTime(event.getString("departure"));
                    } else {
                        plannedTime = parseDateTime(event.getString("departure"));
                    }
                    final JSONObject lineObj = event.getJSONObject("line");
                    final Line line = parseLine(lineObj);
                    Position position = null;
                    final JSONObject post = event.optJSONObject("post");
                    if (post != null) {
                        final String positionStr = post.getString("name");
                        // examples for post:
                        // (U) Gleis 2
                        // Bonn Hauptbahnhof (ZOB) - Bussteig C4
                        // A
                        position = new Position(positionStr.substring(positionStr.lastIndexOf(' ') + 1));
                    }
                    final Location destination = new Location(LocationType.STATION, null /* id */, null /* place */,
                            lineObj.getString("direction"));

                    final LineDestination lineDestination = new LineDestination(line, destination);
                    if (!lines.contains(lineDestination)) {
                        lines.add(lineDestination);
                    }
                    final Departure d = new Departure(plannedTime, predictedTime, line, position, destination, null,
                            null);
                    departures.add(d);
                }

                queryLinesForStation(location.id, lines);

                result.stationDepartures.add(new StationDepartures(location, departures, lines));
            }

            return result;
        } catch (final JSONException x) {
            throw new RuntimeException("cannot parse: '" + page + "' on " + uri, x);
        } catch (final ParseException e) {
            throw new RuntimeException("cannot parse: '" + page + "' on " + uri, e);
        }
    }

    @Override
    public SuggestLocationsResult suggestLocations(final CharSequence constraint) throws IOException {
        final CharSequence page = httpClient.get(HttpUrl.parse(uri), Charsets.UTF_8);

        try {
            
        } catch (final JSONException x) {
            throw new RuntimeException("cannot parse: '" + page + "' on " + uri, x);
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
        final int hour = c.get(Calendar.HOUR_OF_DAY);
        final int minute = c.get(Calendar.MINUTE);
        final int second = c.get(Calendar.SECOND);
		uri.append("&departure_date=");
		uri.append(ParserUtils.urlEncode(String.format("%02d.%02d.%04d", day, month, year)));
        
		// by appending "&search_by=cities", you could retrieve trips by citiy ID instead of station ID
        uri.append("&from=").append(from.id);
        uri.append("&to=").append(to.id);
        final CharSequence page = httpClient.get(HttpUrl.parse(uri.toString()), Charsets.UTF_8);

        try {
            final List<Trip> trips = new ArrayList<Trip>();
            final JSONObject head = new JSONObject(page.toString());
            final JSONArray tripObjs = head.getJSONArray("trips");

            if (tripObjs.length() == 0) {
                //TODO return empty list of trips
            } else if (tripObjs.length() > 1) {
                //TODO error: search did not return exactly one station to exactly one other station
            } else {
                final tripObj = tripObjs.getJSONObject(0);
                // maybe we should check if from.id == tripObj.getJSONObject("from").getString("id")
                // and the same for to.id
                
                for (int i = 0; i < itemObjs.length(); i++) {
                    final JSONObj itemObj = itemObjs.getJSONObject(i);
                    
                    final Stop firstStop = new Stop(from, true,
                        // Java uses millisecond timestamp whereas this is in seconds, so *1000
                        new Date(itemObj.getJSONObject("departure").getInt("timestamp") * 1000),
                        null, null, null);
                    final Stop lastStop = new Stop(to, false,
                        new Date(itemObj.getJSONObject("arrival").getInt("timestamp") * 1000),
                        null, null, null);

                    List<Leg> legs = new ArrayList<Leg>();
                    if (itemObj.getString("type") == direct) { // trip has a single leg
                        legs.add(new Trip.Public(null, null, firstStop, lastStop, null, null));
                    }
                    else if (itemObj.getString("type") == "interconnection") { // trip has mutliple legs
                        final JSONArray transferObjs = itemObj.getJSONObject("interconnection_transfers");
                        final List<Stop> departures = new ArrayList<Stop>();
                        final List<Stop> arrivals = new ArrayList<Stop>();
                        departures.add(firstStop);
                        for (int j = 0; j < transferObjs.length(); j++) {
                            // parse transfer and add arrival, departure to lists
                            final JSONObject transferObj = transferObjs.getJSONObject(j);
                            final Location location = getStationById(transferObj.getInt("station_id"));
                            
                            arrivals.add(new Stop(location, false,
                                new Date(transferObj.getJSONObject("arrival").getInt("timestamp") * 1000),
                                null, null, null);
                            departures.add(new Stop(location, true,
                                new Date(transferObj.getJSONObject("departure").getInt("timestamp") * 1000),
                                null, null, null);
                        }
                        arrivals.add(lastStop);
                        for (int j = 0; j < departures.size(); j++) {
                            legs.add(new Trip.Public(null, null, departures.get(j), arrivals.get(j), null, null));
                        }
                    } else {
                        //TODO error: unknown type
                    }
                }
            }
        } catch (final JSONException x) {
            throw new RuntimeException("cannot parse: '" + page + "' on " + uri, x);
        }
    }

    @Override
    public QueryTripsResult queryMoreTrips(QueryTripsContext context, boolean later) throws IOException {
        Context ctx = (Context) context;
        if (later) {
            return queryTrips(ctx.from, ctx.via, ctx.to, ctx.getLastDeparture(), true, ctx.products, null, null, null,
                    null);
        } else {
            return queryTrips(ctx.from, ctx.via, ctx.to, ctx.getFirstArrival(), false, ctx.products, null, null, null,
                    null);
        }
    }

    @Override
    public Style lineStyle(final @Nullable String network, final @Nullable Product product,
            final @Nullable String label) {
        if (product == Product.BUS && label != null && label.startsWith("SB")) {
            return super.lineStyle(network, product, "SB");
        }

        return super.lineStyle(network, product, label);
    }

    @Override
    public Point[] getArea() throws IOException {
        return new Point[] { new Point(50937531, 6960279) };
    }

    private static Product productFromLineNumber(String number) {
        if (number.startsWith("I") || number.startsWith("E")) {
            return Product.HIGH_SPEED_TRAIN;
        } else if (number.startsWith("R") || number.startsWith("MRB") || number.startsWith("DPN")) {
            return Product.REGIONAL_TRAIN;
        } else if (number.startsWith("S") && !number.startsWith("SB") && !number.startsWith("SEV")) {
            return Product.SUBURBAN_TRAIN;
        } else if (number.startsWith("U")) {
            return Product.SUBWAY;
        } else if (number.length() <= 2 && !number.startsWith("N")) {
            return Product.TRAM;
        } else {
            return Product.BUS;
        }
    }

    private Line parseLine(JSONObject line) throws JSONException {
        final String number = processLineNumber(line.getString("number"));
        final Product productObj = parseProduct(line.getString("product"), number);
        final Style style = lineStyle("vrs", productObj, number);
        return new Line(null /* id */, NetworkId.VRS.toString(), productObj, number, style);
    }

    private static String processLineNumber(final String number) {
        if (number.startsWith("AST ") || number.startsWith("VRM ") || number.startsWith("VRR ")) {
            return number.substring(4);
        } else if (number.startsWith("AST") || number.startsWith("VRM") || number.startsWith("VRR")) {
            return number.substring(3);
        } else if (number.startsWith("TaxiBus ")) {
            return number.substring(8);
        } else if (number.startsWith("TaxiBus")) {
            return number.substring(7);
        } else if (number.equals("Schienen-Ersatz-Verkehr (SEV)")) {
            return "SEV";
        } else {
            return number;
        }
    }

    private static Product parseProduct(String product, String number) {
        if (product.equals("LongDistanceTrains")) {
            return Product.HIGH_SPEED_TRAIN;
        } else if (product.equals("RegionalTrains")) {
            return Product.REGIONAL_TRAIN;
        } else if (product.equals("SuburbanTrains")) {
            return Product.SUBURBAN_TRAIN;
        } else if (product.equals("Underground") || product.equals("LightRail") && number.startsWith("U")) {
            return Product.SUBWAY;
        } else if (product.equals("LightRail")) {
            // note that also the Skytrain (Flughafen Düsseldorf Bahnhof - Flughafen Düsseldorf Terminan
            // and Schwebebahn Wuppertal (line 60) are both returned as product "LightRail".
            return Product.TRAM;
        } else if (product.equals("Bus") || product.equals("CommunityBus")
                || product.equals("RailReplacementServices")) {
            return Product.BUS;
        } else if (product.equals("Boat")) {
            return Product.FERRY;
        } else if (product.equals("OnDemandServices")) {
            return Product.ON_DEMAND;
        } else {
            throw new IllegalArgumentException("unknown product: '" + product + "'");
        }
    }

    private static String generateProducts(Set<Product> products) {
        StringBuilder ret = new StringBuilder();
        Iterator<Product> it = products.iterator();
        while (it.hasNext()) {
            final Product product = it.next();
            final String productStr = generateProduct(product);
            if (ret.length() > 0 && !ret.substring(ret.length() - 1).equals(",") && !productStr.isEmpty()) {
                ret.append(",");
            }
            ret.append(productStr);
        }
        return ret.toString();
    }

    private static String generateProduct(Product product) {
        switch (product) {
        case BUS:
            // can't filter for RailReplacementServices although this value is valid in API responses
            return "Bus,CommunityBus";
        case CABLECAR:
            // no mapping in VRS
            return "";
        case FERRY:
            return "Boat";
        case HIGH_SPEED_TRAIN:
            return "LongDistanceTrains";
        case ON_DEMAND:
            return "OnDemandServices";
        case REGIONAL_TRAIN:
            return "RegionalTrains";
        case SUBURBAN_TRAIN:
            return "SuburbanTrains";
        case SUBWAY:
            return "LightRail,Underground";
        case TRAM:
            return "LightRail";
        default:
            throw new IllegalArgumentException("unknown product: '" + product + "'");
        }
    }

    public static LocationWithPosition parseLocationAndPosition(JSONObject location) throws JSONException {
        final LocationType locationType;
        String id = null;
        String name = null;
        String position = null;
        if (location.has("id")) {
            locationType = LocationType.STATION;
            id = location.getString("id");
            name = location.getString("name");
            for (Pattern pattern : nameWithPositionPatterns) {
                Matcher matcher = pattern.matcher(name);
                if (matcher.matches()) {
                    name = matcher.group(1);
                    position = matcher.group(2);
                    break;
                }
            }
        } else if (location.has("street")) {
            locationType = LocationType.ADDRESS;
            name = (location.getString("street") + " " + location.getString("number")).trim();
        } else if (location.has("name")) {
            locationType = LocationType.POI;
            id = location.getString("tempId");
            name = location.getString("name");
        } else if (location.has("x") && location.has("y")) {
            locationType = LocationType.ANY;
        } else {
            throw new IllegalArgumentException("unknown location JSONObject: " + location);
        }
        String place = location.optString("city", null);
        if (place != null) {
            if (location.has("district") && !location.getString("district").isEmpty()) {
                place += "-" + location.getString("district");
            }
        }
        final int lat = (int) Math.round(location.optDouble("x", 0) * 1E6);
        final int lon = (int) Math.round(location.optDouble("y", 0) * 1E6);
        return new LocationWithPosition(new Location(locationType, id, lat, lon, place, name),
                position != null ? new Position(position.substring(position.lastIndexOf(" ") + 1)) : null);
    }

    private String generateLocation(Location loc, List<Location> ambiguous) throws IOException {
        if (loc == null) {
            return null;
        } else if (loc.id != null) {
            return loc.id;
        } else if (loc.lat != 0 && loc.lon != 0) {
            return String.format(Locale.ENGLISH, "%f,%f", loc.lat / 1E6, loc.lon / 1E6);
        } else {
            SuggestLocationsResult suggestLocationsResult = suggestLocations(loc.name);
            final List<Location> suggestedLocations = suggestLocationsResult.getLocations();
            if (suggestedLocations.size() == 1) {
                return suggestedLocations.get(0).id;
            } else {
                ambiguous.addAll(suggestedLocations);
                return null;
            }
        }
    }

    private final static void appendDate(final StringBuilder uri, final Date time) {
        final Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.setTime(time);
        final int year = c.get(Calendar.YEAR);
        final int month = c.get(Calendar.MONTH) + 1;
        final int day = c.get(Calendar.DAY_OF_MONTH);
        final int hour = c.get(Calendar.HOUR_OF_DAY);
        final int minute = c.get(Calendar.MINUTE);
        final int second = c.get(Calendar.SECOND);
            }

    private final static Date parseDateTime(final String dateTimeStr) throws ParseException {
        return new SimpleDateFormat("yyyy-MM-dd'T'kk:mm:ssZ")
                .parse(dateTimeStr.substring(0, dateTimeStr.lastIndexOf(':')) + "00");
    }
}
