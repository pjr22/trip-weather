# Timezone Management Testing Plan

## Overview
This document outlines testing procedures for the timezone management refactoring changes. Since we cannot run the application directly, these tests should be performed when the application is eventually executed.

## Important Updates Based on Testing Feedback

1. **Route Calculation Changes**:
   - API requests now include `timezoneName` instead of `timezoneOffset`
   - Backend will determine the correct offset based on waypoint's date/time
   - This simplifies the frontend by removing DST calculation logic from route requests

2. **User Location as Waypoint**:
   - When user adds their location as a waypoint, ensure all timezone fields are populated
   - Test the "Add my location" button in the waypoint popup
   - Verify complete timezone information is stored for user's location

## Test Scenarios

### 1. Basic Waypoint Creation Tests

#### Test 1.1: New Waypoint from Map Click
**Steps:**
1. Click on the map to add a new waypoint
2. Verify that all timezone fields are populated in the waypoint object
3. Check that the timezone abbreviation displayed is the standard time abbreviation

**Expected Results:**
- All 5 timezone fields are stored (name, stdOffset, dstOffset, stdAbbr, dstAbbr)
- Display shows standard time abbreviation (e.g., "MST" for Denver in January)

#### Test 1.2: New Waypoint from Search
**Steps:**
1. Use the search feature to find a location
2. Select a search result to add as waypoint
3. Verify timezone information is correctly extracted and stored

**Expected Results:**
- All timezone fields from the search result are stored
- Location name and timezone are properly displayed

### 2. Date/Time Change Tests

#### Test 2.1: Standard Time Date
**Steps:**
1. Add a waypoint in a timezone that observes DST
2. Set date to January 15th (standard time)
3. Verify the displayed timezone abbreviation

**Expected Results:**
- Shows standard time abbreviation (e.g., "MST" for Denver)
- Timezone offset in route calculation uses standard offset

#### Test 2.2: Daylight Time Date
**Steps:**
1. Add a waypoint in a timezone that observes DST
2. Set date to July 15th (daylight time)
3. Verify the displayed timezone abbreviation

**Expected Results:**
- Shows daylight time abbreviation (e.g., "MDT" for Denver)
- Timezone offset in route calculation uses daylight offset

### 3. Route Calculation Tests

#### Test 3.1: Route with Mixed Timezones
**Steps:**
1. Create waypoints in different timezones
2. Set dates during standard time for some, daylight time for others
3. Calculate route
4. Check the API request payload (in browser dev tools)

**Expected Results:**
- Each waypoint includes correct timezone offset based on its date
- API request includes timezoneOffset field for each waypoint

#### Test 3.2: Route Response Handling
**Steps:**
1. Calculate a route that returns timezone information
2. Verify that waypoints are updated with any timezone data from the response

**Expected Results:**
- Waypoint timezone fields are updated from route response if provided
- Display updates to show correct abbreviations

### 4. Backward Compatibility Tests

#### Test 4.1: Existing Waypoints
**Steps:**
1. Load existing route data (if available)
2. Verify that waypoints with only old timezone field still work

**Expected Results:**
- Application doesn't crash with incomplete timezone data
- Falls back gracefully to available timezone information

#### Test 4.2: Mixed Data
**Steps:**
1. Create waypoints with complete and incomplete timezone data
2. Verify consistent behavior

**Expected Results:**
- Complete data shows proper DST handling
- Incomplete data shows standard time abbreviation

### 5. Edge Case Tests

#### Test 5.1: Missing Timezone Data
**Steps:**
1. Add a waypoint where timezone data is not available
2. Verify application behavior

**Expected Results:**
- No crashes or errors
- Graceful fallback to empty or default timezone display

#### Test 5.2: Invalid Dates
**Steps:**
1. Set invalid dates for waypoints
2. Verify timezone handling

**Expected Results:**
- No crashes
- Defaults to standard time abbreviation

## Code Verification Checklist

### Files to Verify:
- [ ] WaypointManager.js - All timezone fields handled in waypoint operations
- [ ] Helpers.js - parseLocationData extracts all timezone fields
- [ ] WaypointRenderer.js - formatWaypointTime uses stored data
- [ ] RouteManager.js - Route calculation includes timezone offset
- [ ] TimezoneUtils.js - New functions work correctly
- [ ] LocationService.js - extractLocationFromFeature returns all fields

### Integration Points:
- [ ] Map click → Waypoint creation → Timezone storage
- [ ] Search → Waypoint creation → Timezone storage
- [ ] Date/time change → Timezone abbreviation update
- [ ] Route calculation → Timezone offset inclusion
- [ ] Display → Correct abbreviation based on date

## Browser Console Checks

When testing, monitor the browser console for:
1. Any errors related to timezone handling
2. Warnings about deprecated functions
3. Log messages showing timezone data flow
4. API request payloads with timezone offsets

## Manual Verification Steps

1. Add waypoints in different timezones (e.g., Denver, New York, Phoenix)
2. Set dates in both January and July
3. Verify correct abbreviations (MST/MDT, EST/EDT, MST)
4. Calculate a route and check network requests for timezone offsets
5. Change dates and verify abbreviations update correctly

## Success Criteria

The refactoring is successful when:
1. All timezone information from API is stored in waypoints
2. Correct abbreviation (STD/DST) is displayed based on date
3. Route calculations include appropriate timezone offsets
4. No client-side timezone calculations are performed
5. Application gracefully handles missing or incomplete timezone data
6. Existing functionality remains intact (backward compatibility)