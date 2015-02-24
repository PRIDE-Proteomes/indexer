import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import uk.ac.ebi.pride.proteomes.db.core.api.peptide.PeptideRepository;
import uk.ac.ebi.pride.proteomes.index.ProteomesIndexer;
import uk.ac.ebi.pride.proteomes.index.model.PeptiForm;
import uk.ac.ebi.pride.proteomes.index.service.ProteomesIndexService;
import uk.ac.ebi.pride.proteomes.index.service.ProteomesSearchService;

public class Playground {

    public static void main(String[] args) {

        ApplicationContext ctx = new ClassPathXmlApplicationContext("spring/app-context.xml");
        ProteomesIndexService indexService = (ProteomesIndexService)ctx.getBean("indexService");
        ProteomesSearchService searchService = (ProteomesSearchService)ctx.getBean("searchService");
        PeptideRepository repo = ctx.getBean(PeptideRepository.class);

        ProteomesIndexer indexer = new ProteomesIndexer(indexService, repo);


//        indexer.deleteAll();
        indexer.indexProteomes();

//        Page<PeptiForm> result = searchService.findByQueryAndFilterTaxid("RTGGLSSTK", null, new PageRequest(0,10));
//        System.out.println("results: " + result.getTotalElements());

        System.out.println("All done.");
    }


}
