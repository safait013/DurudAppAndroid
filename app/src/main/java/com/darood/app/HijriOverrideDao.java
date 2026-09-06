package com.darood.app;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface HijriOverrideDao {

    /** Finds the manual override for one Gregorian date (the stable lookup key). */
    @Query("SELECT * FROM hijri_overrides WHERE gregorianDate = :gregorianDate LIMIT 1")
    HijriOverride find(String gregorianDate);

    /** All manual overrides, newest Gregorian date first. */
    @Query("SELECT * FROM hijri_overrides ORDER BY gregorianDate DESC")
    List<HijriOverride> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(HijriOverride override);

    @Query("DELETE FROM hijri_overrides WHERE gregorianDate = :gregorianDate")
    void delete(String gregorianDate);
}