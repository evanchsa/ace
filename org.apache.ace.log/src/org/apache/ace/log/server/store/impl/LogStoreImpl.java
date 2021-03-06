/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ace.log.server.store.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.LockSupport;

import org.apache.ace.feedback.Descriptor;
import org.apache.ace.feedback.Event;
import org.apache.ace.log.server.store.LogStore;
import org.apache.ace.range.Range;
import org.apache.ace.range.SortedRangeSet;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.event.EventAdmin;

/**
 * A simple implementation of the LogStore interface.
 */
public class LogStoreImpl implements LogStore, ManagedService {

    private static final String MAXIMUM_NUMBER_OF_EVENTS = "MaxEvents";

    private volatile EventAdmin m_eventAdmin; /* Injected by dependency manager */

    // the dir to store logs in - init is in the start method
    private final File m_dir;
    private final String m_name;
    private int m_maxEvents = 0;

    private final ConcurrentMap<String, Set<Long>> m_locks = new ConcurrentHashMap<String, Set<Long>>();
    private final Map<String, Long> m_fileToID = new HashMap<String, Long>();

    public LogStoreImpl(File baseDir, String name) {
        m_name = name;
        m_dir = new File(baseDir, "store");
    }

    /*
     * init the dir in which to store logs in - thows IllegalArgumentException if we can't get it.
     */
    protected void start() throws IOException {
        if (!m_dir.isDirectory() && !m_dir.mkdirs()) {
            throw new IllegalArgumentException("Need valid dir");
        }
    }

    public List<Event> get(Descriptor descriptor) throws IOException {
        obtainLock(descriptor.getTargetID(), descriptor.getStoreID());
        try {
            return getInternal(descriptor);
        }
        finally {
            releaseLock(descriptor.getTargetID(), descriptor.getStoreID());
        }
    }

    /**
     * Retrieve the events that match the given descriptor. This method relies on external locking, the caller should
     * take care of that.
     * 
     * @param descriptor
     *            the events to retrieve
     * @return the events that match
     * @throws IOException
     *             if anything goes wrong
     */
    private List<Event> getInternal(Descriptor descriptor) throws IOException {
        final List<Event> result = new ArrayList<Event>();
        final SortedRangeSet set = descriptor.getRangeSet();
        BufferedReader in = null;
        try {
            File log = new File(new File(m_dir, targetIDToFilename(descriptor.getTargetID())), String.valueOf(descriptor.getStoreID()));
            if (!log.isFile()) {
                return result;
            }
            in = new BufferedReader(new FileReader(log));
            String file = log.getAbsolutePath();
            long counter = 0;
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                Event event = new Event(line);
                long id = event.getID();
                if ((counter != -1) && ++counter == id) {

                }
                else {
                    counter = -1;
                }
                if (set.contains(id)) {
                    result.add(event);
                }
            }
            if (counter < 1) {
                m_fileToID.remove(file);
            }
            else {
                m_fileToID.put(file, counter);
            }
        }
        finally {
            if (in != null) {
                try {
                    in.close();
                }
                catch (Exception ex) {
                    // Not much we can do
                }
            }
        }
        return result;
    }

    public Descriptor getDescriptor(String targetID, long logID) throws IOException {
        Long high = m_fileToID.get(new File(new File(m_dir, targetIDToFilename(targetID)), String.valueOf(logID)).getAbsolutePath());
        if (high != null) {
            Range r = new Range(1, high);
            return new Descriptor(targetID, logID, new SortedRangeSet(r.toRepresentation()));
        }
        List<Event> events = get(new Descriptor(targetID, logID, SortedRangeSet.FULL_SET));

        long[] idsArray = new long[events.size()];
        int i = 0;
        for (Event e : events) {
            idsArray[i++] = e.getID();
        }
        return new Descriptor(targetID, logID, new SortedRangeSet(idsArray));
    }

    public List<Descriptor> getDescriptors(String targetID) throws IOException {
        File dir = new File(m_dir, targetIDToFilename(targetID));
        List<Descriptor> result = new ArrayList<Descriptor>();
        if (!dir.isDirectory()) {
            return result;
        }

        for (String name : notNull(dir.list())) {
            result.add(getDescriptor(targetID, Long.parseLong(name)));
        }

        return result;
    }

    public List<Descriptor> getDescriptors() throws IOException {
        List<Descriptor> result = new ArrayList<Descriptor>();
        for (String name : notNull(m_dir.list())) {
            result.addAll(getDescriptors(filenameToTargetID(name)));
        }
        return result;
    }

    public void put(List<Event> events) throws IOException {
        Map<String, Map<Long, List<Event>>> sorted = sort(events);
        for (String targetID : sorted.keySet()) {
            for (Long logID : sorted.get(targetID).keySet()) {
                obtainLock(targetID, logID);
                try {
                    put(targetID, logID, sorted.get(targetID).get(logID));
                }
                finally {
                    releaseLock(targetID, logID);
                }
            }
        }
    }

    /**
     * Add a list of events to the log of the given ids. This method relies on external locking, the caller should take
     * care of that.
     * 
     * @param targetID
     *            the id of the target to append to its log.
     * @param logID
     *            the id of the given target log.
     * @param list
     *            a list of events to store.
     * @throws java.io.IOException
     *             in case of any error.
     */
    protected void put(String targetID, Long logID, List<Event> list) throws IOException {
        if ((list == null) || (list.size() == 0)) {
            // nothing to add, so return
            return;
        }
        // we actually need to distinguish between two scenarios here:
        // 1. we can append events at the end of the existing file
        // 2. we need to insert events in the existing file (meaning we have to
        // rewrite basically the whole file)
        String file = new File(new File(m_dir, targetIDToFilename(targetID)), String.valueOf(logID)).getAbsolutePath();
        Long highest = m_fileToID.get(file);
        boolean cached = false;
        if (highest != null) {
            if (highest.longValue() + 1 == list.get(0).getID()) {
                cached = true;
            }
        }
        List<Event> events = null;
        if (!cached) {
            events = getInternal(new Descriptor(targetID, logID, SortedRangeSet.FULL_SET));

            // remove duplicates first
            list.removeAll(events);
        }

        boolean removeEvents = false;
        if (m_maxEvents > 0 && m_maxEvents < list.size() + events.size()) {
            removeEvents = true;
        }

        if (!removeEvents && list.size() == 0) {
            // nothing to add or remove anymore, so return
            return;
        }

        PrintWriter out = null;
        try {
            File dir = new File(m_dir, targetIDToFilename(targetID));
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Unable to create backup store.");
            }
            if (!removeEvents && (cached || ((events.size() == 0) || (events.get(events.size() - 1).getID() < list.get(0).getID())))) {
                // we can append to the existing file without need to remove records
                out = new PrintWriter(new FileWriter(new File(dir, logID.toString()), true));
            }
            else {
                // we have to merge the lists
                list.addAll(events);
                // and sort
                Collections.sort(list);
                // and remove if necessary
                for (int i = 0; i < m_maxEvents; i++) {
                    if (list.size() > 0) {
                        list.remove(0);
                    }
                }
                out = new PrintWriter(new FileWriter(new File(dir, logID.toString())));
            }
            long high = 0;
            for (Event event : list) {
                String representation = event.toRepresentation();
                out.println(representation);
                if (high < event.getID()) {
                    high = event.getID();
                }
                else {
                    high = Long.MAX_VALUE;
                }
                // send (eventadmin)event about a new (log)event being stored
                Dictionary<String, Object> props = new Hashtable<String, Object>();
                props.put(LogStore.EVENT_PROP_LOGNAME, m_name);
                props.put(LogStore.EVENT_PROP_LOG_EVENT, event);
                m_eventAdmin.postEvent(new org.osgi.service.event.Event(LogStore.EVENT_TOPIC, props));
            }
            if ((cached) && (high < Long.MAX_VALUE)) {
                m_fileToID.put(file, new Long(high));
            }
            else {
                m_fileToID.remove(file);
            }
        }
        finally {
            try {
                out.close();
            }
            catch (Exception ex) {
                // Not much we can do
            }
        }
    }

    /**
     * Sort the given list of events into a map of maps according to the targetID and the logID of each event.
     * 
     * @param events
     *            a list of events to sort.
     * @return a map of maps that maps target ids to a map that maps log ids to a list of events that have those ids.
     */
    @SuppressWarnings("boxing")
    protected Map<String, Map<Long, List<Event>>> sort(List<Event> events) {
        Map<String, Map<Long, List<Event>>> result = new HashMap<String, Map<Long, List<Event>>>();
        for (Event event : events) {
            Map<Long, List<Event>> target = result.get(event.getTargetID());

            if (target == null) {
                target = new HashMap<Long, List<Event>>();
                result.put(event.getTargetID(), target);
            }

            List<Event> list = target.get(event.getStoreID());
            if (list == null) {
                list = new ArrayList<Event>();
                target.put(event.getStoreID(), list);
            }

            list.add(event);
        }
        return result;
    }

    /*
     * throw IOException in case the target is null else return the target.
     */
    private <T> T notNull(T target) throws IOException {
        if (target == null) {
            throw new IOException("Unknown IO error while trying to access the store.");
        }
        return target;
    }

    private static String filenameToTargetID(String filename) {
        byte[] bytes = new byte[filename.length() / 2];
        for (int i = 0; i < (filename.length() / 2); i++) {
            String hexValue = filename.substring(i * 2, (i + 1) * 2);
            bytes[i] = Byte.parseByte(hexValue, 16);
        }

        String result = null;
        try {
            result = new String(bytes, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            // UTF-8 is a mandatory encoding; this will never happen.
        }
        return result;
    }

    private static String targetIDToFilename(String targetID) {
        StringBuilder result = new StringBuilder();

        try {
            for (Byte b : targetID.getBytes("UTF-8")) {
                String hexValue = Integer.toHexString(b.intValue());
                if (hexValue.length() % 2 == 0) {
                    result.append(hexValue);
                }
                else {
                    result.append('0').append(hexValue);
                }
            }
        }
        catch (UnsupportedEncodingException e) {
            // UTF-8 is a mandatory encoding; this will never happen.
        }

        return result.toString();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void updated(Dictionary settings) throws ConfigurationException {
        if (settings != null) {
            String maximumNumberOfEvents = (String) settings.get(MAXIMUM_NUMBER_OF_EVENTS);
            if (maximumNumberOfEvents != null) {
                try {
                    m_maxEvents = Integer.parseInt(maximumNumberOfEvents);
                }
                catch (NumberFormatException nfe) {
                    throw new ConfigurationException(MAXIMUM_NUMBER_OF_EVENTS, "is not a number");
                }
            }
        }
    }

    @Override
    public void clean() throws IOException {
        // check if we event might have to cleanup anything
        if (m_maxEvents <= 0) {
            return;
        }
        // create a list of unique targets and their logs
        Map<String, Set<Long>> allTargetsAndLogs = new HashMap<String, Set<Long>>();
        for (Descriptor descriptor : getDescriptors()) {
            Set<Long> logs = allTargetsAndLogs.get(descriptor.getTargetID());
            if (logs == null) {
                logs = new HashSet<Long>();
                allTargetsAndLogs.put(descriptor.getTargetID(), logs);
            }
            logs.add(descriptor.getStoreID());
        }

        // cleanup per log
        for (String targetID : allTargetsAndLogs.keySet()) {
            for (Long logId : allTargetsAndLogs.get(targetID)) {
                clean(targetID, logId);
            }
        }
    }

    private void clean(String targetID, Long logID) throws IOException {
        obtainLock(targetID, logID);
        try {
            List<Event> events = getInternal(new Descriptor(targetID, logID, SortedRangeSet.FULL_SET));
            if (events.size() > m_maxEvents) {
                for (int i = 0; i < m_maxEvents; i++) {
                    if (events.size() > 0) {
                        events.remove(0);
                    }
                }
            }
            put(targetID, logID, events);
        }
        finally {
            releaseLock(targetID, logID);
        }
    }

    private void obtainLock(String targetID, long logID) throws IOException {
        Set<Long> newLockedLogs = new HashSet<Long>();
        Set<Long> lockedLogs = m_locks.putIfAbsent(targetID, newLockedLogs);
        if (lockedLogs == null) {
            lockedLogs = newLockedLogs;
        }
        boolean alreadyLocked;

        synchronized (lockedLogs) {
            alreadyLocked = lockedLogs.contains(logID);
            if (!alreadyLocked) {
                // lock it now, we are working on it
                lockedLogs.add(logID);
            }
        }

        // try to obtain the lock if we could not lock it on the first try
        if (alreadyLocked) {
            int nrOfTries = 0;
            while (alreadyLocked && nrOfTries++ < 10000) {
                try {
                    Thread.sleep(1);
                }
                catch (InterruptedException exception) {
                    // Restore interrupted flag...
                    Thread.currentThread().interrupt();
                    break;
                }

                synchronized (lockedLogs) {
                    alreadyLocked = lockedLogs.contains(logID);
                    if (!alreadyLocked) {
                        // lock it now, we are working on it
                        lockedLogs.add(logID);
                    }
                }
            }
            // if the log is still locked throw an exception
            if (alreadyLocked) {
                throw new IOException("Could not obtain a lock for the store " + logID + " of target " + targetID);
            }
        }
    }

    private void releaseLock(String targetID, Long logID) {
        Set<Long> lockedLogs = m_locks.get(targetID);
        synchronized (lockedLogs) {
            lockedLogs.remove(logID);
        }
    }
}
