import org.apache.solr.client.solrj.SolrServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.PeptideRepository;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.group.PeptideGroupRepository;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.protein.PeptideProteinRepository;
import uk.ac.ebi.pride.proteomes.index.ProteomesIndexer;
import uk.ac.ebi.pride.proteomes.index.service.ProteomesIndexService;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;

public class Playground {

    public static void main(String[] args) {

        ApplicationContext ctx = new ClassPathXmlApplicationContext("spring/app-context.xml");
        ProteomesIndexService indexService = (ProteomesIndexService)ctx.getBean("indexService");
        SolrServer solrServer = (SolrServer)ctx.getBean("proteomesSolrServer");
        PeptideRepository pepRepo = ctx.getBean(PeptideRepository.class);
        PeptideGroupRepository pepGroupRepo = ctx.getBean(PeptideGroupRepository.class);
        PeptideProteinRepository pepProtRepo = ctx.getBean(PeptideProteinRepository.class);

        ProteomesIndexer indexer = new ProteomesIndexer(indexService, pepRepo, pepGroupRepo, pepProtRepo, solrServer);

        Calendar cal = Calendar.getInstance();
        DateFormat df = new SimpleDateFormat("dd:MM:yy:HH:mm:ss");
        cal.setTimeInMillis(System.currentTimeMillis());
        System.out.println("Started indexing process at: " + df.format(cal.getTime()));

//        indexer.deleteAll();

        try {
            long start = System.currentTimeMillis();
            indexer.indexBySymbolicPeptides(false);
            long end = System.currentTimeMillis();
            System.out.println("Indexing time [ms]: " + (end-start));
        } catch (Exception e) {
            cal.setTimeInMillis(System.currentTimeMillis());
            System.out.println("Unexpected exception at: " + df.format(cal.getTime()) + " Exception: " + e.toString());
            e.printStackTrace();
        }



        System.out.println("All done.");
    }


}
