package migrations.service.mcs.V18;

import com.contrastsecurity.cassandra.migration.api.JavaMigration;
import com.datastax.driver.core.*;
import migrations.service.mcs.V18.jobs.DataCopier;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static migrations.common.TableCopier.hasNextRow;

/**
 * Created by Tarek on 4/26/19
 */
public class V18_1__copy_data_from_replica implements JavaMigration {

    public static final int THREADS = 25;
    private PreparedStatement selectDistinctPartitionKeysStatement;


    private void initStatements(Session session) {
        selectDistinctPartitionKeysStatement = session.prepare("SELECT DISTINCT provider_id,dataset_id FROM latest_provider_dataset_rep_rev_replica");
        selectDistinctPartitionKeysStatement.setConsistencyLevel(ConsistencyLevel.QUORUM);
    }

    @Override
    public void migrate(Session session) {
        try {
            initStatements(session);
            ExecutorService executorService = Executors.newFixedThreadPool(THREADS);
            ResultSet distinctPartitions = session.execute(selectDistinctPartitionKeysStatement.bind());
            Iterator<Row> distinctPartitionIterator = distinctPartitions.iterator();
            Set<Future<String>> set = new HashSet<>();
            while (hasNextRow(distinctPartitionIterator)) {
                Row disRow = distinctPartitionIterator.next();
                DataCopier dataCopier = new DataCopier(session, disRow);
                set.add(executorService.submit(dataCopier));
                if (set.size() >= THREADS) {
                    waitToFinish(set);
                    set.clear();
                }
            }
            waitToFinish(set);
            executorService.shutdown();
        } catch (Exception e) {
            System.err.println("The migration was not completed successfully:" + e.getMessage() + ". Because of:" + e.getCause() + " .Please clean and restart again!!");
        }
    }

    void waitToFinish(Set<Future<String>> set) throws ExecutionException, InterruptedException {
        for (Future<String> future : set) {
            System.out.println(future.get());
        }
    }
}