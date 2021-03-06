/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2019 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.stock.service;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.service.ProductService;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockLocationLine;
import com.axelor.apps.stock.db.StockRules;
import com.axelor.apps.stock.db.repo.StockLocationLineRepository;
import com.axelor.apps.stock.db.repo.StockLocationRepository;
import com.axelor.apps.stock.db.repo.StockRulesRepository;
import com.axelor.apps.stock.service.config.StockConfigService;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import com.google.inject.servlet.RequestScoped;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.persistence.Query;

@RequestScoped
public class StockLocationServiceImpl implements StockLocationService {

  protected StockLocationRepository stockLocationRepo;

  protected StockLocationLineService stockLocationLineService;

  protected ProductRepository productRepo;

  protected Set<Long> locationIdSet = new HashSet<>();

  @Inject
  public StockLocationServiceImpl(
      StockLocationRepository stockLocationRepo,
      StockLocationLineService stockLocationLineService,
      ProductRepository productRepo) {
    this.stockLocationRepo = stockLocationRepo;
    this.stockLocationLineService = stockLocationLineService;
    this.productRepo = productRepo;
  }

  @Override
  public StockLocation getDefaultReceiptStockLocation(Company company) {
    try {
      StockConfigService stockConfigService = Beans.get(StockConfigService.class);
      StockConfig stockConfig = stockConfigService.getStockConfig(company);
      return stockConfigService.getReceiptDefaultStockLocation(stockConfig);
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public StockLocation getPickupDefaultStockLocation(Company company) {
    try {
      StockConfigService stockConfigService = Beans.get(StockConfigService.class);
      StockConfig stockConfig = stockConfigService.getStockConfig(company);
      return stockConfigService.getPickupDefaultStockLocation(stockConfig);
    } catch (Exception e) {
      return null;
    }
  }

  public List<StockLocation> getNonVirtualStockLocations() {
    return stockLocationRepo
        .all()
        .filter("self.typeSelect != ?1", StockLocationRepository.TYPE_VIRTUAL)
        .fetch();
  }

  @Override
  public BigDecimal getQty(Long productId, Long locationId, String qtyType) throws AxelorException {
    if (productId != null) {
      Product product = productRepo.find(productId);
      Unit productUnit = product.getUnit();
      UnitConversionService unitConversionService = Beans.get(UnitConversionService.class);

      if (locationId == null) {
        List<StockLocation> stockLocations = getNonVirtualStockLocations();
        if (!stockLocations.isEmpty()) {
          BigDecimal qty = BigDecimal.ZERO;
          for (StockLocation stockLocation : stockLocations) {
            StockLocationLine stockLocationLine =
                stockLocationLineService.getOrCreateStockLocationLine(
                    stockLocationRepo.find(stockLocation.getId()), productRepo.find(productId));

            if (stockLocationLine != null) {
              Unit stockLocationLineUnit = stockLocationLine.getUnit();
              qty =
                  qty.add(
                      qtyType.equals("real")
                          ? stockLocationLine.getCurrentQty()
                          : stockLocationLine.getFutureQty());

              if (productUnit != null && !productUnit.equals(stockLocationLineUnit)) {
                qty =
                    unitConversionService.convert(
                        stockLocationLineUnit, productUnit, qty, qty.scale(), product);
              }
            }
          }
          return qty;
        }
      } else {
        StockLocationLine stockLocationLine =
            stockLocationLineService.getOrCreateStockLocationLine(
                stockLocationRepo.find(locationId), productRepo.find(productId));

        if (stockLocationLine != null) {
          Unit stockLocationLineUnit = stockLocationLine.getUnit();
          BigDecimal qty = BigDecimal.ZERO;

          qty =
              qtyType.equals("real")
                  ? stockLocationLine.getCurrentQty()
                  : stockLocationLine.getFutureQty();

          if (productUnit != null && !productUnit.equals(stockLocationLineUnit)) {
            qty =
                unitConversionService.convert(
                    stockLocationLineUnit, productUnit, qty, qty.scale(), product);
          }
          return qty;
        }
      }
    }

    return null;
  }

  @Override
  public BigDecimal getRealQty(Long productId, Long locationId) throws AxelorException {
    return getQty(productId, locationId, "real");
  }

  @Override
  public BigDecimal getFutureQty(Long productId, Long locationId) throws AxelorException {
    return getQty(productId, locationId, "future");
  }

  @Override
  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
  public void computeAvgPriceForProduct(Product product) {
    Long productId = product.getId();
    String query =
        "SELECT new list(self.id, self.avgPrice, self.currentQty) FROM StockLocationLine as self "
            + "WHERE self.product.id = "
            + productId
            + " AND self.stockLocation.typeSelect != "
            + StockLocationRepository.TYPE_VIRTUAL;
    int scale = Beans.get(AppBaseService.class).getNbDecimalDigitForUnitPrice();
    BigDecimal productAvgPrice = BigDecimal.ZERO;
    BigDecimal qtyTot = BigDecimal.ZERO;
    List<List<Object>> results = JPA.em().createQuery(query).getResultList();
    if (results.isEmpty()) {
      return;
    }
    for (List<Object> result : results) {
      BigDecimal avgPrice = (BigDecimal) result.get(1);
      BigDecimal qty = (BigDecimal) result.get(2);
      productAvgPrice = productAvgPrice.add(avgPrice.multiply(qty));
      qtyTot = qtyTot.add(qty);
    }
    if (qtyTot.compareTo(BigDecimal.ZERO) == 0) {
      return;
    }
    productAvgPrice = productAvgPrice.divide(qtyTot, scale, BigDecimal.ROUND_HALF_UP);
    product.setAvgPrice(productAvgPrice);
    if (product.getCostTypeSelect() == ProductRepository.COST_TYPE_AVERAGE_PRICE) {
      product.setCostPrice(productAvgPrice);
      if (product.getAutoUpdateSalePrice()) {
        Beans.get(ProductService.class).updateSalePrice(product);
      }
    }
    productRepo.save(product);
  }

  public List<Long> getBadStockLocationLineId() {

    List<StockLocationLine> stockLocationLineList =
        Beans.get(StockLocationLineRepository.class)
            .all()
            .filter("self.stockLocation.typeSelect = 1 OR self.stockLocation.typeSelect = 2")
            .fetch();

    List<Long> idList = new ArrayList<>();

    StockRulesRepository stockRulesRepository = Beans.get(StockRulesRepository.class);

    for (StockLocationLine stockLocationLine : stockLocationLineList) {
      StockRules stockRules =
          stockRulesRepository
              .all()
              .filter(
                  "self.stockLocation = ?1 AND self.product = ?2",
                  stockLocationLine.getStockLocation(),
                  stockLocationLine.getProduct())
              .fetchOne();
      if (stockRules != null
          && stockLocationLine.getFutureQty().compareTo(stockRules.getMinQty()) < 0) {
        idList.add(stockLocationLine.getId());
      }
    }

    if (idList.isEmpty()) {
      idList.add(0L);
    }

    return idList;
  }

  @Override
  public Set<Long> getContentStockLocationIds(StockLocation stockLocation) {
    locationIdSet = new HashSet<>();
    if (stockLocation != null) {
      List<StockLocation> stockLocations = getAllLocationAndSubLocation(stockLocation, true);
      for (StockLocation item : stockLocations) {
        locationIdSet.add(item.getId());
      }
    } else {
      locationIdSet.add(0L);
    }

    return locationIdSet;
  }

  public List<StockLocation> getAllLocationAndSubLocation(
      StockLocation stockLocation, boolean isVirtualInclude) {

    List<StockLocation> resultList = new ArrayList<>();

    if (isVirtualInclude) {
      for (StockLocation subLocation :
          stockLocationRepo
              .all()
              .filter("self.parentStockLocation.id = :stockLocationId")
              .bind("stockLocationId", stockLocation.getId())
              .fetch()) {

        resultList.addAll(this.getAllLocationAndSubLocation(subLocation, isVirtualInclude));
      }
    } else {
      for (StockLocation subLocation :
          stockLocationRepo
              .all()
              .filter(
                  "self.parentStockLocation.id = :stockLocationId AND self.typeSelect != :virtual")
              .bind("stockLocationId", stockLocation.getId())
              .bind("virtual", StockLocationRepository.TYPE_VIRTUAL)
              .fetch()) {

        resultList.addAll(this.getAllLocationAndSubLocation(subLocation, isVirtualInclude));
      }
    }
    resultList.add(stockLocation);

    return resultList;
  }

  @Override
  public BigDecimal getStockLocationValue(StockLocation stockLocation) {

    Query query =
        JPA.em()
            .createQuery(
                "SELECT SUM( self.currentQty * CASE WHEN (product.costTypeSelect = 3) THEN "
                    + "(self.avgPrice) ELSE (self.product.costPrice) END ) AS value "
                    + "FROM StockLocationLine AS self "
                    + "WHERE self.stockLocation.id =:id");
    query.setParameter("id", stockLocation.getId());

    List<?> result = query.getResultList();
    return result.get(0) == null
        ? BigDecimal.ZERO
        : ((BigDecimal) result.get(0)).setScale(2, BigDecimal.ROUND_HALF_EVEN);
  }
}
