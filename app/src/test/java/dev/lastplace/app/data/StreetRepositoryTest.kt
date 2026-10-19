package dev.lastplace.app.data

import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetDao
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalTime

class StreetRepositoryTest {
    private val dao = mock(StreetDao::class.java)
    private val settings = mock(SettingsRepository::class.java)
    private val repository = StreetRepository(dao, settings)
    private val street = Street(id = 1, name = "Street", lat = 0.0, lng = 0.0)
    private val rule = CleaningRule(streetId = 1, dayOfWeek = 2, startMinuteOfDay = 9 * 60, endMinuteOfDay = 14 * 60)

    @Test
    fun updatesReplaceRulesAtomicallyThenRetainOnlyCurrentlySavedEndTimes() = runTest {
        `when`(dao.getStreetsWithRules()).thenReturn(listOf(StreetWithRules(street, listOf(rule))))
        repository.updateStreet(street, listOf(rule))
        verify(dao).updateStreetWithRules(street, listOf(rule))
        verify(settings).retainReparkingTimes(setOf(LocalTime.of(14, 0)))
    }

    @Test
    fun deletionPrunesPreferencesAndRemovingAllStreetsClearsThem() = runTest {
        `when`(dao.getStreetsWithRules()).thenReturn(emptyList())
        repository.deleteStreet(street)
        verify(dao).deleteStreet(street)
        verify(settings).retainReparkingTimes(emptySet())
    }

    @Test
    fun togglingAFavouriteDoesNotReplaceStreetDataOrCleaningRules() = runTest {
        repository.toggleFavourite(1)
        verify(dao).toggleFavourite(1)
    }

    @Test
    fun editingAStreetPreservesItsLatestFavouriteSelection() = runTest {
        val transactionalDao = object : StreetDao by dao {
            override suspend fun updateStreetWithRules(street: Street, rules: List<CleaningRule>) {
                super<StreetDao>.updateStreetWithRules(street, rules)
            }
        }
        val favourite = street.copy(isFavourite = true)
        `when`(dao.getStreetWithRules(1)).thenReturn(StreetWithRules(favourite, listOf(rule)))
        val edited = street.copy(name = "Renamed")
        transactionalDao.updateStreetWithRules(edited, listOf(rule))
        verify(dao).updateStreet(edited.copy(isFavourite = true))
        verify(dao).deleteRulesForStreet(1)
        verify(dao).insertRule(rule)
    }
}
