package de.anomic.kelondro;

// a collectionIndex is an index to kelondroRowCollection objects
// such a collection ist defined by the following parameters
// - chunksize
// - chunkcount
// each of such a collection is stored in a byte[] which may or may not have space for more chunks
// than already exists in such an array. To store these arrays, we reserve entries in kelondroArray
// database files. There will be a set of array files for different sizes of the collection arrays.
// the 1st file has space for <loadfactor> chunks, the 2nd file for <loadfactor> * <loadfactor> chunks,
// the 3rd file for <loadfactor>^^3 chunks, and the n-th file for <loadfactor>^^n chunks.
// if the loadfactor is 4, then we have the following capacities:
// file 0:    4
// file 1:   16
// file 2:   64
// file 3:  256
// file 4: 1024
// file 5: 4096
// file 6:16384
// file 7:65536
// the maximum number of such files is called the partitions number.
// we don't want that these files grow too big, an kelondroOutOfLimitsException is throws if they
// are oversized.
// the collection arrays may be migration to another size during run-time, which means that not only the
// partitions as mentioned above are maintained, but also a set of "shadow-partitions", that represent old
// partitions and where data is read only and slowly migrated to the default partitions.

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.anomic.index.indexContainer;
import de.anomic.server.serverFileUtils;
import de.anomic.server.logging.serverLog;

public class kelondroCollectionIndex {

    protected kelondroIndex index;
    int keylength;
    private File          path;
    private String        filenameStub;
    private int           loadfactor;
    private Map           arrays; // Map of (partitionNumber"-"chunksize)/kelondroFixedWidthArray - Objects
    private kelondroRow   payloadrow; // definition of the payload (chunks inside the collections)
    //  private int partitions;  // this is the maxmimum number of array files; yet not used
    
    private static final int idx_col_key        = 0;  // the index
    private static final int idx_col_chunksize  = 1;  // chunksize (number of bytes in a single chunk, needed for migration option)
    private static final int idx_col_chunkcount = 2;  // chunkcount (number of chunks in this collection)
    private static final int idx_col_clusteridx = 3;  // selector for right cluster file, must be >= arrayIndex(chunkcount)
    private static final int idx_col_flags      = 4;  // flags (for future use)
    private static final int idx_col_indexpos   = 5;  // indexpos (position in array file)
    private static final int idx_col_lastread   = 6;  // a time stamp, update time in days since 1.1.2000
    private static final int idx_col_lastwrote  = 7;  // a time stamp, update time in days since 1.1.2000

    private static kelondroRow indexRow(int keylength, kelondroOrder payloadOrder) {
        return new kelondroRow(
            "byte[] key-" + keylength + "," +
            "int chunksize-4 {b256}," +
            "int chunkcount-4 {b256}," +
            "byte clusteridx-1 {b256}," +
            "byte flags-1 {b256}," +
            "int indexpos-4 {b256}," +
            "short lastread-2 {b256}, " +
            "short lastwrote-2 {b256}",
            payloadOrder, 0
            );
    }
    
    public kelondroRow payloadRow() {
        return this.payloadrow;
    }
    
    private static String fillZ(String s, int len) {
        while (s.length() < len) s = "0" + s;
        return s;
    }
    
    private static File arrayFile(File path, String filenameStub, int loadfactor, int chunksize, int partitionNumber, int serialNumber) {
        String lf = fillZ(Integer.toHexString(loadfactor).toUpperCase(), 2);
        String cs = fillZ(Integer.toHexString(chunksize).toUpperCase(), 4);
        String pn = fillZ(Integer.toHexString(partitionNumber).toUpperCase(), 2);
        String sn = fillZ(Integer.toHexString(serialNumber).toUpperCase(), 2);
        return new File(path, filenameStub + "." + lf + "." + cs + "." + pn + "." + sn + ".kca"); // kelondro collection array
    }
   
    private static File propertyFile(File path, String filenameStub, int loadfactor, int chunksize) {
        String lf = fillZ(Integer.toHexString(loadfactor).toUpperCase(), 2);
        String cs = fillZ(Integer.toHexString(chunksize).toUpperCase(), 4);
        return new File(path, filenameStub + "." + lf + "." + cs + ".properties");
    }
    
    public kelondroCollectionIndex(File path, String filenameStub, int keyLength, kelondroOrder indexOrder,
                                   long buffersize, long preloadTime,
                                   int loadfactor, kelondroRow rowdef) throws IOException {
        // the buffersize is number of bytes that are only used if the kelondroFlexTable is backed up with a kelondroTree
        this.path = path;
        this.filenameStub = filenameStub;
        this.keylength = keyLength;
        this.payloadrow = rowdef;
        this.loadfactor = loadfactor;

        boolean ramIndexGeneration = false;
        boolean fileIndexGeneration = !(new File(path, filenameStub + ".index").exists());
        if (ramIndexGeneration) index = new kelondroRowSet(indexRow(keyLength, indexOrder), 0);
        if (fileIndexGeneration) index = new kelondroFlexTable(path, filenameStub + ".index", buffersize, preloadTime, indexRow(keyLength, indexOrder));
                   
        // open array files
        this.arrays = new HashMap(); // all entries will be dynamically created with getArray()
        if (((fileIndexGeneration) || (ramIndexGeneration))) {
            serverLog.logFine("STARTUP", "STARTED INITIALIZATION OF NEW COLLECTION INDEX. THIS WILL TAKE SOME TIME");
            openAllArrayFiles(((fileIndexGeneration) || (ramIndexGeneration)), indexOrder);
        }
        
        // open/create index table
        if (index == null) index = openIndexFile(path, filenameStub, indexOrder, buffersize, preloadTime, loadfactor, rowdef);
    }
    
    private void openAllArrayFiles(boolean indexGeneration, kelondroOrder indexOrder) throws IOException {
        String[] list = this.path.list();
        kelondroFixedWidthArray array;
        
        kelondroRow irow = indexRow(keylength, indexOrder);
        int t = kelondroRowCollection.daysSince2000(System.currentTimeMillis());
        for (int i = 0; i < list.length; i++) if (list[i].endsWith(".kca")) {

            // open array
            int pos = list[i].indexOf('.');
            if (pos < 0) continue;
            int chunksize       = Integer.parseInt(list[i].substring(pos +  4, pos +  8), 16);
            int partitionNumber = Integer.parseInt(list[i].substring(pos +  9, pos + 11), 16);
            int serialNumber    = Integer.parseInt(list[i].substring(pos + 12, pos + 14), 16);
            try {
                array = openArrayFile(partitionNumber, serialNumber, true);
            } catch (IOException e) {
                e.printStackTrace();
                continue;
            }
            
            // remember that we opened the array
            arrays.put(partitionNumber + "-" + chunksize, array);
            
            if ((index != null) && (indexGeneration)) {
                // loop over all elements in array and create index entry for each row
                kelondroRow.EntryIndex aentry;
                kelondroRow.Entry      ientry;
                Iterator ei = array.contentRows(-1);
                byte[] key;
                long start = System.currentTimeMillis();
                long lastlog = start;
                int count = 0;
                while (ei.hasNext()) {
                    aentry = (kelondroRow.EntryIndex) ei.next();
                    key = aentry.getColBytes(0);
                    assert (key != null);
                    if (key == null) continue; // skip deleted entries
                    kelondroRowSet indexrows = new kelondroRowSet(this.payloadrow, aentry.getColBytes(1));
                    ientry = irow.newEntry();
                    ientry.setCol(idx_col_key,        key);
                    ientry.setCol(idx_col_chunksize,  chunksize);
                    ientry.setCol(idx_col_chunkcount, indexrows.size());
                    ientry.setCol(idx_col_clusteridx, (byte) partitionNumber);
                    ientry.setCol(idx_col_flags,      (byte) 0);
                    ientry.setCol(idx_col_indexpos,   aentry.index());
                    ientry.setCol(idx_col_lastread,   t);
                    ientry.setCol(idx_col_lastwrote,  t);
                    index.addUnique(ientry);
                    count++;
                    
                    // write a log
                    if (System.currentTimeMillis() - lastlog > 30000) {
                        serverLog.logFine("STARTUP", "created " + count + " RWI index entries. " + (((System.currentTimeMillis() - start) * (array.size() + array.free() - count) / count) / 60000) + " minutes remaining for this array");
                        lastlog = System.currentTimeMillis();
                    }
                }
            }
        }
    }
    
    private kelondroIndex openIndexFile(File path, String filenameStub, kelondroOrder indexOrder,
            long buffersize, long preloadTime,
            int loadfactor, kelondroRow rowdef) throws IOException {
        // open/create index table
        kelondroIndex theindex = new kelondroCache(new kelondroFlexTable(path, filenameStub + ".index", buffersize / 2, preloadTime, indexRow(keylength, indexOrder)), buffersize / 2, true, false);

        // save/check property file for this array
        File propfile = propertyFile(path, filenameStub, loadfactor, rowdef.objectsize());
        Map props = new HashMap();
        if (propfile.exists()) {
            props = serverFileUtils.loadHashMap(propfile);
            String stored_rowdef = (String) props.get("rowdef");
            if ((stored_rowdef == null) || (!(rowdef.subsumes(new kelondroRow(stored_rowdef, null, 0))))) {
                System.out.println("FATAL ERROR: stored rowdef '" + stored_rowdef + "' does not match with new rowdef '" + 
                        rowdef + "' for array cluster '" + path + "/" + filenameStub + "'");
                System.exit(-1);
            }
        }
        props.put("rowdef", rowdef.toString());
        serverFileUtils.saveMap(propfile, props, "CollectionIndex properties");
        
        return theindex;
    }
    
    private kelondroFixedWidthArray openArrayFile(int partitionNumber, int serialNumber, boolean create) throws IOException {
        File f = arrayFile(path, filenameStub, loadfactor, payloadrow.objectsize(), partitionNumber, serialNumber);
        int load = arrayCapacity(partitionNumber);
        kelondroRow rowdef = new kelondroRow(
                "byte[] key-" + keylength + "," +
                "byte[] collection-" + (kelondroRowCollection.exportOverheadSize + load * this.payloadrow.objectsize()),
                index.row().objectOrder,
                0
                );
        if ((!(f.exists())) && (!create)) return null;
        kelondroFixedWidthArray a = new kelondroFixedWidthArray(f, rowdef, 0);
        serverLog.logFine("STARTUP", "opened array file " + f + " with " + a.size() + " RWIs");
        return a;
    }
    
    private kelondroFixedWidthArray getArray(int partitionNumber, int serialNumber, int chunksize) {
        String accessKey = partitionNumber + "-" + chunksize;
        kelondroFixedWidthArray array = (kelondroFixedWidthArray) arrays.get(accessKey);
        if (array != null) return array;
        try {
            array = openArrayFile(partitionNumber, serialNumber, true);
        } catch (IOException e) {
            return null;
        }
        arrays.put(accessKey, array);
        return array;
    }
    
    private int arrayCapacity(int arrayCounter) {
        int load = this.loadfactor;
        for (int i = 0; i < arrayCounter; i++) load = load * this.loadfactor;
        return load;
    }
    
    private int arrayIndex(int requestedCapacity) throws kelondroOutOfLimitsException{
        // the requestedCapacity is the number of wanted chunks
        int load = 1, i = 0;
        while (true) {
            load = load * this.loadfactor;
            if (load >= requestedCapacity) return i;
            i++;
        }
    }
    
    public synchronized int size() throws IOException {
        return index.size();
    }
    
    public int minMem() {
        // calculate a minimum amount of memory that is necessary to use the collection
        // during runtime (after the index was initialized)
        
        // caclculate an upper limit (not the correct size) of the maximum number of indexes for a wordHash
        // this is computed by the size of the biggest used collection
        int m = 1;
        for (int i = 0; i < arrays.size(); i++) m = m * this.loadfactor;

        // this must be multiplied with the payload size
        // and doubled for necessary memory transformation during sort operation
        return 2 * m * this.payloadrow.objectsize;
    }
    
    private kelondroRow.Entry putnew(byte[] key, kelondroRowCollection collection) throws IOException {
        // the collection is new
        int newPartitionNumber = arrayIndex(collection.size());
        kelondroRow.Entry indexrow = index.row().newEntry();
        kelondroFixedWidthArray array = getArray(newPartitionNumber, 0, this.payloadrow.objectsize());

        // define row
        kelondroRow.Entry arrayEntry = array.row().newEntry();
        arrayEntry.setCol(0, key);
        arrayEntry.setCol(1, collection.exportCollection());

        // write a new entry in this array
        int newRowNumber = array.add(arrayEntry);

        // store the new row number in the index
        indexrow.setCol(idx_col_key, key);
        indexrow.setCol(idx_col_chunksize, this.payloadrow.objectsize());
        indexrow.setCol(idx_col_chunkcount, collection.size());
        indexrow.setCol(idx_col_clusteridx, (byte) newPartitionNumber);
        indexrow.setCol(idx_col_flags, (byte) 0);
        indexrow.setCol(idx_col_indexpos, (long) newRowNumber);
        indexrow.setCol(idx_col_lastread, kelondroRowCollection.daysSince2000(System.currentTimeMillis()));
        indexrow.setCol(idx_col_lastwrote, kelondroRowCollection.daysSince2000(System.currentTimeMillis()));

        // after calling this method there mus be a index.addUnique(indexrow);
        return indexrow;
    }
    
    private void putreplace(
            byte[] key, kelondroRowCollection collection, kelondroRow.Entry indexrow,
            int serialNumber, int chunkSize,
            int partitionNumber, int rownumber) throws IOException {
        // we don't need a new slot, just write collection into the old one

        // find array file
        kelondroFixedWidthArray array = getArray(partitionNumber, serialNumber, chunkSize);

        // define new row
        kelondroRow.Entry arrayEntry = array.row().newEntry();
        arrayEntry.setCol(0, key);
        arrayEntry.setCol(1, collection.exportCollection());

        // overwrite entry in this array
        array.set(rownumber, arrayEntry);

        // update the index entry
        indexrow.setCol(idx_col_chunkcount, collection.size());
        indexrow.setCol(idx_col_clusteridx, (byte) partitionNumber);
        indexrow.setCol(idx_col_lastwrote, kelondroRowCollection.daysSince2000(System.currentTimeMillis()));
        
        // after calling this method there mus be a index.put(indexrow);
    }
    
    private void puttransit(
            byte[] key, kelondroRowCollection collection, kelondroRow.Entry indexrow,
            int serialNumber, int chunkSize,
            int oldPartitionNumber, int oldRownumber,
            int newPartitionNumber) throws IOException {
        // we need a new slot, that means we must first delete the old entry
        // find array file
        kelondroFixedWidthArray array = getArray(oldPartitionNumber, serialNumber, chunkSize);

        // delete old entry
        array.remove(oldRownumber);

        // write a new entry in the other array
        array = getArray(newPartitionNumber, 0, this.payloadrow.objectsize());
        
        // define row
        kelondroRow.Entry arrayEntry = array.row().newEntry();
        arrayEntry.setCol(0, key);
        arrayEntry.setCol(1, collection.exportCollection());
        
        // write a new entry in this array
        int newRowNumber = array.add(arrayEntry);
        
        // store the new row number in the index
        indexrow.setCol(idx_col_chunkcount, collection.size());
        indexrow.setCol(idx_col_clusteridx, (byte) newPartitionNumber);
        indexrow.setCol(idx_col_indexpos, (long) newRowNumber);
        indexrow.setCol(idx_col_lastwrote, kelondroRowCollection.daysSince2000(System.currentTimeMillis()));

        // after calling this method there mus be a index.put(indexrow);
    }
    
    public synchronized void put(byte[] key, kelondroRowCollection collection) throws IOException, kelondroOutOfLimitsException {

        // first find an old entry, if one exists
        kelondroRow.Entry indexrow = index.get(key);
        
        if (indexrow == null) {
            // create new row and index entry
            if ((collection != null) && (collection.size() > 0)) {
                indexrow = putnew(key, collection); // modifies indexrow
                index.addUnique(indexrow);
            }
            return;
        }
            
        // overwrite the old collection
        // read old information
        int oldchunksize       = (int) indexrow.getColLong(idx_col_chunksize);  // needed only for migration
        int oldchunkcount      = (int) indexrow.getColLong(idx_col_chunkcount); // the number if rows in the collection
        int oldrownumber       = (int) indexrow.getColLong(idx_col_indexpos);   // index of the entry in array
        int oldPartitionNumber = (int) indexrow.getColByte(idx_col_clusteridx); // points to array file
        assert (oldPartitionNumber >= arrayIndex(oldchunkcount));
        int oldSerialNumber = 0;

        if ((collection == null) || (collection.size() == 0)) {
            // delete the index entry and the array
            kelondroFixedWidthArray array = getArray(oldPartitionNumber, oldSerialNumber, oldchunksize);
            array.remove(oldrownumber);
            index.remove(key);
            return;
        }

        int newPartitionNumber = arrayIndex(collection.size());

        // see if we need new space or if we can overwrite the old space
        if (oldPartitionNumber == newPartitionNumber) {
            putreplace(
                    key, collection, indexrow,
                    oldSerialNumber, this.payloadrow.objectsize(),
                    oldPartitionNumber, oldrownumber); // modifies indexrow
        } else {
            puttransit(
                    key, collection, indexrow,
                    oldSerialNumber, this.payloadrow.objectsize(),
                    oldPartitionNumber, oldrownumber,
                    newPartitionNumber); // modifies indexrow
        }
        index.put(indexrow); // write modified indexrow
    }
    
    public synchronized void mergeMultiple(List /* of indexContainer */ containerList) throws IOException, kelondroOutOfLimitsException {
        // merge a bulk of index containers
        // this method should be used to optimize the R/W head path length
        
        // separate the list in two halves:
        // - containers that do not exist yet in the collection
        // - containers that do exist in the collection and must be merged
        Iterator i = containerList.iterator();
        indexContainer container;
        byte[] key;
        ArrayList newContainer = new ArrayList();
        ArrayList existingContainer = new ArrayList();
        kelondroRow.Entry indexrow;
        while (i.hasNext()) {
            container = (indexContainer) i.next();
            
            if ((container == null) || (container.size() == 0)) continue;
            key = container.getWordHash().getBytes();
            
            // first find an old entry, if one exists
            indexrow = index.get(key);
            if (indexrow == null) {
                newContainer.add(new Object[]{key, container});
            } else {
                existingContainer.add(new Object[]{key, container, indexrow});
            }
        }
        
        // now iterate through the container lists and execute merges
        // this is done in such a way, that there is a optimized path for the R/W head
        
        // write new containers
        i = newContainer.iterator();
        Object[] record;
        while (i.hasNext()) {
            record = (Object[]) i.next(); // {byte[], indexContainer}
            mergeNew((byte[]) record[0], (indexContainer) record[1]);
        }
        
        // merge existing containers
        i = existingContainer.iterator();
        ArrayList indexrows = new ArrayList();
        while (i.hasNext()) {
            record = (Object[]) i.next(); // {byte[], indexContainer, kelondroRow.Entry}
            indexrow = (kelondroRow.Entry) record[2];
            mergeExisting((byte[]) record[0], (indexContainer) record[1], indexrow); // modifies indexrow
            indexrows.add(indexrow); // indexrows are collected and written later as block
        }
        index.putMultiple(indexrows, new Date()); // write modified indexrows in optimized manner
    }
    
    public synchronized void merge(indexContainer container) throws IOException, kelondroOutOfLimitsException {
        if ((container == null) || (container.size() == 0)) return;
        byte[] key = container.getWordHash().getBytes();
        
        // first find an old entry, if one exists
        kelondroRow.Entry indexrow = index.get(key);
        if (indexrow == null) {
            mergeNew(key, container);
        } else {
            mergeExisting(key, container, indexrow); // modifies indexrow
            index.put(indexrow); // write modified indexrow
        }
    }
    
    private void mergeNew(byte[] key, kelondroRowCollection collection) throws IOException, kelondroOutOfLimitsException {
        // create new row and index entry
        
        kelondroRow.Entry indexrow = putnew(key, collection); // modifies indexrow
        index.addUnique(indexrow); // write modified indexrow
    }
    
    private void mergeExisting(byte[] key, kelondroRowCollection collection, kelondroRow.Entry indexrow) throws IOException, kelondroOutOfLimitsException {
        // merge with the old collection
        // attention! this modifies the indexrow entry which must be written with index.put(indexrow) afterwards!
        
        // read old information
        int oldchunksize       = (int) indexrow.getColLong(idx_col_chunksize);  // needed only for migration
        int oldchunkcount      = (int) indexrow.getColLong(idx_col_chunkcount); // the number if rows in the collection
        int oldrownumber       = (int) indexrow.getColLong(idx_col_indexpos);   // index of the entry in array
        int oldPartitionNumber = (int) indexrow.getColByte(idx_col_clusteridx); // points to array file
        assert (oldPartitionNumber >= arrayIndex(oldchunkcount));
        int oldSerialNumber = 0;

        // load the old collection and join it
        kelondroRowSet oldcollection = getwithparams(indexrow, oldchunksize, oldchunkcount, oldPartitionNumber, oldrownumber, oldSerialNumber, false);
                
        // join with new collection
        oldcollection.addAllUnique(collection);
        oldcollection.shape();
        oldcollection.uniq(); // FIXME: not clear if it would be better to insert the collection with put to avoid double-entries
        oldcollection.trim();
        collection = oldcollection;

        int newPartitionNumber = arrayIndex(collection.size());

        // see if we need new space or if we can overwrite the old space
        if (oldPartitionNumber == newPartitionNumber) {
            putreplace(
                    key, collection, indexrow,
                    oldSerialNumber, this.payloadrow.objectsize(),
                    oldPartitionNumber, oldrownumber); // modifies indexrow
        } else {
            puttransit(
                    key, collection, indexrow,
                    oldSerialNumber, this.payloadrow.objectsize(),
                    oldPartitionNumber, oldrownumber,
                    newPartitionNumber); // modifies indexrow
        }
        
    }
    
    public synchronized int remove(byte[] key, Set removekeys) throws IOException, kelondroOutOfLimitsException {
        
        if ((removekeys == null) || (removekeys.size() == 0)) return 0;
        
        // first find an old entry, if one exists
        kelondroRow.Entry indexrow = index.get(key);
        
        if (indexrow == null) return 0;
            
        // overwrite the old collection
        // read old information
        int oldchunksize       = (int) indexrow.getColLong(idx_col_chunksize);  // needed only for migration
        int oldchunkcount      = (int) indexrow.getColLong(idx_col_chunkcount); // the number if rows in the collection
        int oldrownumber       = (int) indexrow.getColLong(idx_col_indexpos);   // index of the entry in array
        int oldPartitionNumber = (int) indexrow.getColByte(idx_col_clusteridx); // points to array file
        assert (oldPartitionNumber >= arrayIndex(oldchunkcount));
        int oldSerialNumber = 0;

        int removed = 0;
        assert (removekeys != null);
        // load the old collection and remove keys
        kelondroRowSet oldcollection = getwithparams(indexrow, oldchunksize, oldchunkcount, oldPartitionNumber, oldrownumber, oldSerialNumber, false);

        // remove the keys from the set
        Iterator i = removekeys.iterator();
        Object k;
        while (i.hasNext()) {
            k = i.next();
            if ((k instanceof byte[]) && (oldcollection.remove((byte[]) k) != null)) removed++;
            if ((k instanceof String) && (oldcollection.remove(((String) k).getBytes()) != null)) removed++;
        }
        oldcollection.shape();
        oldcollection.trim();

        if (oldcollection.size() == 0) {
            // delete the index entry and the array
            kelondroFixedWidthArray array = getArray(oldPartitionNumber, oldSerialNumber, oldchunksize);
            array.remove(oldrownumber);
            index.remove(key);
            return removed;
        }

        int newPartitionNumber = arrayIndex(oldcollection.size());

        // see if we need new space or if we can overwrite the old space
        if (oldPartitionNumber == newPartitionNumber) {
            putreplace(
                    key, oldcollection, indexrow,
                    oldSerialNumber, this.payloadrow.objectsize(),
                    oldPartitionNumber, oldrownumber); // modifies indexrow
        } else {
            puttransit(
                    key, oldcollection, indexrow,
                    oldSerialNumber, this.payloadrow.objectsize(),
                    oldPartitionNumber, oldrownumber,
                    newPartitionNumber); // modifies indexrow
        }
        index.put(indexrow); // write modified indexrow
        return removed;
    }
    
    public synchronized int indexSize(byte[] key) throws IOException {
        kelondroRow.Entry indexrow = index.get(key);
        if (indexrow == null) return 0;
        return (int) indexrow.getColLong(idx_col_chunkcount);
    }
    
    public synchronized boolean has(byte[] key) throws IOException {
        return index.has(key);
    }
    
    public synchronized kelondroRowSet get(byte[] key) throws IOException {
        // find an entry, if one exists
        kelondroRow.Entry indexrow = index.get(key);
        if (indexrow == null) return null;
        kelondroRowSet col = getdelete(indexrow, false);
        assert (col != null);
        return col;
    }
    
    public synchronized kelondroRowSet delete(byte[] key) throws IOException {
        // find an entry, if one exists
        kelondroRow.Entry indexrow = index.remove(key);
        if (indexrow == null) return null;
        kelondroRowSet removedCollection = getdelete(indexrow, true);
        assert (removedCollection != null);
        return removedCollection;
    }

    protected kelondroRowSet getdelete(kelondroRow.Entry indexrow, boolean remove) throws IOException {
        // call this only within a synchronized(index) environment
        
        // read values
        int chunksize       = (int) indexrow.getColLong(idx_col_chunksize);
        int chunkcount      = (int) indexrow.getColLong(idx_col_chunkcount);
        int rownumber       = (int) indexrow.getColLong(idx_col_indexpos);
        int partitionnumber = (int) indexrow.getColByte(idx_col_clusteridx);
        assert(partitionnumber >= arrayIndex(chunkcount));
        int serialnumber = 0;
        
        return getwithparams(indexrow, chunksize, chunkcount, partitionnumber, rownumber, serialnumber, remove);
    }

    private kelondroRowSet getwithparams(kelondroRow.Entry indexrow, int chunksize, int chunkcount, int clusteridx, int rownumber, int serialnumber, boolean remove) throws IOException {
        // open array entry
        kelondroFixedWidthArray array = getArray(clusteridx, serialnumber, chunksize);
        kelondroRow.Entry arrayrow = array.get(rownumber);
        if (arrayrow == null) throw new kelondroException(arrayFile(this.path, this.filenameStub, this.loadfactor, chunksize, clusteridx, serialnumber).toString(), "array does not contain expected row");

        // read the row and define a collection
        byte[] indexkey = indexrow.getColBytes(idx_col_key);
        byte[] arraykey = arrayrow.getColBytes(0);
        if (!(index.row().objectOrder.wellformed(arraykey))) {
            // cleanup for a bad bug that corrupted the database
            index.remove(indexkey);  // the RowCollection must be considered lost
            array.remove(rownumber); // loose the RowCollection (we don't know how much is lost)
            serverLog.logSevere("kelondroCollectionIndex." + array.filename, "lost a RowCollection because of a bad arraykey");
            return new kelondroRowSet(this.payloadrow, 0);
        }
        kelondroRowSet collection = new kelondroRowSet(this.payloadrow, arrayrow.getColBytes(1)); // FIXME: this does not yet work with different rowdef in case of several rowdef.objectsize()
        if ((!(index.row().objectOrder.wellformed(indexkey))) || (index.row().objectOrder.compare(arraykey, indexkey) != 0)) {
            // check if we got the right row; this row is wrong. Fix it:
            index.remove(indexkey); // the wrong row cannot be fixed
            // store the row number in the index; this may be a double-entry, but better than nothing
            kelondroRow.Entry indexEntry = index.row().newEntry();
            indexEntry.setCol(idx_col_key, arrayrow.getColBytes(0));
            indexEntry.setCol(idx_col_chunksize, this.payloadrow.objectsize());
            indexEntry.setCol(idx_col_chunkcount, collection.size());
            indexEntry.setCol(idx_col_clusteridx, (byte) clusteridx);
            indexEntry.setCol(idx_col_flags, (byte) 0);
            indexEntry.setCol(idx_col_indexpos, (long) rownumber);
            indexEntry.setCol(idx_col_lastread, kelondroRowCollection.daysSince2000(System.currentTimeMillis()));
            indexEntry.setCol(idx_col_lastwrote, kelondroRowCollection.daysSince2000(System.currentTimeMillis()));
            index.put(indexEntry);
            serverLog.logSevere("kelondroCollectionIndex." + array.filename, "array contains wrong row '" + new String(arrayrow.getColBytes(0)) + "', expected is '" + new String(indexrow.getColBytes(idx_col_key)) + "', the row has been fixed");
        }
        int chunkcountInArray = collection.size();
        if (chunkcountInArray != chunkcount) {
            // fix the entry in index
            indexrow.setCol(idx_col_chunkcount, chunkcountInArray);
            index.put(indexrow);
            array.logFailure("INCONSISTENCY in " + arrayFile(this.path, this.filenameStub, this.loadfactor, chunksize, clusteridx, serialnumber).toString() + ": array has different chunkcount than index: index = " + chunkcount + ", array = " + chunkcountInArray + "; the index has been auto-fixed");
        }
        if (remove) array.remove(rownumber); // index is removed in calling method
        return collection;
    }
    
    public synchronized Iterator keycollections(byte[] startKey, boolean rot) {
        // returns an iteration of {byte[], kelondroRowSet} Objects
        try {
            return new keycollectionIterator(startKey, rot);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public class keycollectionIterator implements Iterator {
        
        Iterator indexRowIterator;
        
        public keycollectionIterator(byte[] startKey, boolean rot) throws IOException {
            // iterator of {byte[], kelondroRowSet} Objects
            indexRowIterator = index.rows(true, rot, startKey);
        }
        
        public boolean hasNext() {
            return indexRowIterator.hasNext();
        }

        public Object next() {
            kelondroRow.Entry indexrow = (kelondroRow.Entry) indexRowIterator.next();
            assert (indexrow != null);
            if (indexrow == null) return null;
            try {
                return new Object[]{indexrow.getColBytes(0), getdelete(indexrow, false)};
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        public void remove() {
            indexRowIterator.remove();
        }
        
    }
    
    public synchronized void close() throws IOException {
        this.index.close();
        Iterator i = arrays.values().iterator();
        while (i.hasNext()) {
            ((kelondroFixedWidthArray) i.next()).close();
        }
    }
    
    public static void main(String[] args) {

        // define payload structure
        kelondroRow rowdef = new kelondroRow("byte[] a-10, byte[] b-80", kelondroNaturalOrder.naturalOrder, 0);
        
        File path = new File(args[0]);
        String filenameStub = args[1];
        long buffersize = 10000000;
        long preloadTime = 10000;
        try {
            // initialize collection index
            kelondroCollectionIndex collectionIndex  = new kelondroCollectionIndex(
                        path, filenameStub, 9 /*keyLength*/,
                        kelondroNaturalOrder.naturalOrder, buffersize, preloadTime,
                        4 /*loadfactor*/, rowdef);
            
            // fill index with values
            kelondroRowSet collection = new kelondroRowSet(rowdef, 0);
            collection.addUnique(rowdef.newEntry(new byte[][]{"abc".getBytes(), "efg".getBytes()}));
            collectionIndex.put("erstes".getBytes(), collection);
            
            for (int i = 0; i <= 17; i++) {
                collection = new kelondroRowSet(rowdef, 0);
                for (int j = 0; j < i; j++) {
                    collection.addUnique(rowdef.newEntry(new byte[][]{("abc" + j).getBytes(), "xxx".getBytes()}));
                }
                System.out.println("put key-" + i + ": " + collection.toString());
                collectionIndex.put(("key-" + i).getBytes(), collection);
            }
            
            // extend collections with more values
            for (int i = 0; i <= 17; i++) {
                collection = new kelondroRowSet(rowdef, 0);
                for (int j = 0; j < i; j++) {
                    collection.addUnique(rowdef.newEntry(new byte[][]{("def" + j).getBytes(), "xxx".getBytes()}));
                }
                collectionIndex.merge(new indexContainer("key-" + i, collection));
            }
            
            // printout of index
            collectionIndex.close();
            kelondroFlexTable index = new kelondroFlexTable(path, filenameStub + ".index", buffersize, preloadTime, kelondroCollectionIndex.indexRow(9, kelondroNaturalOrder.naturalOrder));
            index.print();
            index.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
